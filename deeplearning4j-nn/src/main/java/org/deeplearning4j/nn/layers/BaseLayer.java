/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.layers;

import org.deeplearning4j.exception.DL4JInvalidInputException;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.util.*;

/**
 * A layer with parameters
 * @author Adam Gibson
 */
public abstract class BaseLayer<LayerConfT extends org.deeplearning4j.nn.conf.layers.BaseLayer>
                extends AbstractLayer<LayerConfT> {

    protected INDArray paramsFlattened;
    protected INDArray gradientsFlattened;
    protected Map<String, INDArray> params;
    protected transient Map<String, INDArray> gradientViews;

    protected Map<String,INDArray> weightNoiseParams = new HashMap<>();

    public BaseLayer(org.deeplearning4j.nn.conf.layers.BaseLayer conf) {
        super(conf);
    }

    public LayerConfT layerConf() {
        return (LayerConfT) this.conf;
    }

    @Override
    public int numInputs(){
        return 1;
    }

    @Override
    public int numOutputs(){
        return 1;
    }

    @Override
    public Gradients backpropGradient(Gradients epsilons) {
        if(epsilons.size() != 1)
            throw new IllegalArgumentException();
        INDArray epsilon = epsilons.get(0);

        //If this layer is layer L, then epsilon is (w^(L+1)*(d^(L+1))^T) (or equivalent)
        INDArray z = preOutput(true); //Note: using preOutput(INDArray) can't be used as this does a setInput(input) and resets the 'appliedDropout' flag
        INDArray delta = layerConf().getActivationFn().backprop(z, epsilon).getFirst(); //TODO handle activation function params

        if (input.getMask(0) != null) {
            applyMask(delta);
        }

        Gradient ret = new DefaultGradient();

        INDArray weightGrad = gradientViews.get(DefaultParamInitializer.WEIGHT_KEY); //f order
        Nd4j.gemm(input.get(0), delta, weightGrad, true, false, 1.0, 0.0);
        ret.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, weightGrad);

        if(hasBias()){
            INDArray biasGrad = gradientViews.get(DefaultParamInitializer.BIAS_KEY);
            delta.sum(biasGrad, 0); //biasGrad is initialized/zeroed first
            ret.gradientForVariable().put(DefaultParamInitializer.BIAS_KEY, biasGrad);
        }

        INDArray W = getParamWithNoise(DefaultParamInitializer.WEIGHT_KEY, true);

        INDArray epsilonNext = W.mmul(delta.transpose()).transpose();

        weightNoiseParams.clear();

        Gradients g = GradientsFactory.getInstance().create(epsilonNext, ret);
        return backpropPreprocessor(g);
    }

    @Override
    public void update(Gradient gradient) {
        for (String paramType : gradient.gradientForVariable().keySet()) {
            getParam(paramType).subi(gradient.getGradientFor(paramType));
        }
    }

    /**Returns the parameters of the neural network as a flattened row vector
     * @return the parameters of the neural network
     */
    @Override
    public INDArray params() {
        return paramsFlattened;
    }

    @Override
    public INDArray getParam(String param) {
        return params.get(param);
    }

    @Override
    public void setParam(String key, INDArray val) {
        if (params.containsKey(key))
            params.get(key).assign(val);
        else
            params.put(key, val);
    }

    @Override
    public void setParams(INDArray params) {
        if (params == paramsFlattened)
            return; //no op
        setParams(params, 'f');
    }

    protected void setParams(INDArray params, char order) {
        List<String> parameterList = conf.initializer().paramKeys(conf);
        int length = 0;
        for (String s : parameterList)
            length += getParam(s).length();
        if (params.length() != length)
            throw new IllegalArgumentException("Unable to set parameters: must be of length " + length
                            + ", got params of length " + params.length() + " - " + layerId());
        int idx = 0;
        Set<String> paramKeySet = this.params.keySet();
        for (String s : paramKeySet) {
            INDArray param = getParam(s);
            INDArray get = params.get(NDArrayIndex.point(0), NDArrayIndex.interval(idx, idx + param.length()));
            if (param.length() != get.length())
                throw new IllegalStateException("Parameter " + s + " should have been of length " + param.length()
                                + " but was " + get.length() + " - " + layerId());
            param.assign(get.reshape(order, param.shape())); //Use assign due to backprop params being a view of a larger array
            idx += param.length();
        }
    }

    @Override
    public void setParamsViewArray(INDArray params) {
        if (this.params != null && params.length() != numParams())
            throw new IllegalArgumentException("Invalid input: expect params of length " + numParams()
                            + ", got params of length " + params.length() + " - " + layerId());

        this.paramsFlattened = params;
    }

    @Override
    public INDArray getGradientsViewArray() {
        return gradientsFlattened;
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray gradients) {
        if (this.params != null && gradients.length() != numParams())
            throw new IllegalArgumentException("Invalid input: expect gradients array of length " + numParams(true)
                            + ", got array of length " + gradients.length() + " - " + layerId());

        this.gradientsFlattened = gradients;
        this.gradientViews = conf.initializer().getGradientsFromFlattened(conf, gradients);
    }

    @Override
    public void setParamTable(Map<String, INDArray> paramTable) {
        this.params = paramTable;
    }

    @Override
    public Map<String, INDArray> paramTable() {
        return paramTable(false);
    }

    @Override
    public Map<String, INDArray> paramTable(boolean backpropParamsOnly) {
        return params;
    }

    /**
     * Get the parameter, after applying any weight noise (such as DropConnect) if necessary.
     * Note that during training, this will store the post-noise parameters, as these should be used
     * for both forward pass and backprop, for a single iteration.
     * Consequently, the parameters (post noise) should be cleared after each training iteration
     *
     * @param param    Parameter key
     * @param training If true: during training
     * @return The parameter, after applying any noise
     */
    protected INDArray getParamWithNoise(String param, boolean training){
        INDArray p;
        if(layerConf().getWeightNoise() != null){
            if(training && weightNoiseParams.size() > 0 && weightNoiseParams.containsKey(param) ){
                //Re-use these weights for both forward pass and backprop - don't want to use 2 different params here
                //These should be cleared during  backprop
                return weightNoiseParams.get(param);
            } else {
                try (MemoryWorkspace ws = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                    p = layerConf().getWeightNoise().getParameter(this, param, getIterationCount(), getEpochCount(), training);
                }
            }

            if(training){
                //Store for re-use in backprop
                weightNoiseParams.put(param, p);
            }
        } else {
            return getParam(param);
        }

        return p;
    }

    public INDArray preOutput(boolean training) {
        applyPreprocessorIfNecessary(training);
        applyDropOutIfNecessary(training);
        INDArray W = getParamWithNoise(DefaultParamInitializer.WEIGHT_KEY, training);
        INDArray b = getParamWithNoise(DefaultParamInitializer.BIAS_KEY, training);

        //Input validation:
        if (input.get(0).rank() != 2 || input.get(0).columns() != W.rows()) {
            if (input.get(0).rank() != 2) {
                throw new DL4JInvalidInputException("Input is not a matrix; expected matrix (rank 2), got rank "
                                + input.get(0).rank() + " array with shape " + Arrays.toString(input.get(0).shape())
                                + ". Missing preprocessor or wrong input type? " + layerId());
            }
            throw new DL4JInvalidInputException(
                            "Input size (" + input.get(0).columns() + " columns; shape = " + Arrays.toString(input.get(0).shape())
                                            + ") is invalid: does not match layer input size (layer # inputs = "
                                            + W.size(0) + ") " + layerId());
        }


        INDArray ret = input.get(0).mmul(W);
        if(hasBias()){
            ret.addiRowVector(b);
        }

        return ret;
    }

    @Override
    public Activations activate(boolean training) {
        INDArray z = preOutput(training);
        INDArray ret = layerConf().getActivationFn().getActivation(z, training);

        if (input.getMask(0) != null) {
            applyMask(ret);
        }

        return ActivationsFactory.getInstance().create(ret, input.getMask(0), input.getMaskState(0));
    }

    @Override
    public double calcL2(boolean backpropParamsOnly) {
        return l1l2(false, backpropParamsOnly);
    }

    @Override
    public double calcL1(boolean backpropParamsOnly) {
        return l1l2(true, backpropParamsOnly);
    }

    private double l1l2(boolean l1, boolean backpropParamsOnly){
        if(paramsFlattened == null) return 0.0;
        double l1l2 = 0.0;
        for(Map.Entry<String,INDArray> e : paramTable(backpropParamsOnly).entrySet()){
            if(l1){
                double l1Coeff = conf().getL1ByParam(e.getKey());
                if(l1Coeff > 0.0){
                    l1l2 += l1Coeff * e.getValue().norm1Number().doubleValue();
                }
            } else {
                double l2Coeff = conf().getL2ByParam(e.getKey());
                if(l2Coeff > 0.0){
                    //L2 norm: sqrt( sum_i x_i^2 ) -> want sum squared weights, so l2 norm squared
                    double norm2 = e.getValue().norm2Number().doubleValue();
                    l1l2 += 0.5 * l2Coeff * norm2 * norm2;
                }
            }
        }
        return l1l2;
    }


    /**
     * The number of parameters for the model
     *
     * @return the number of parameters for the model
     */
    @Override
    public int numParams() {
        int ret = 0;
        for (INDArray val : params.values())
            ret += val.length();
        return ret;
    }



    @Override
    public String toString() {
        return getClass().getName() + "{" + "conf=" + conf + '}';
    }

    @Override
    public void clear(){
        super.clear();
        weightNoiseParams.clear();
    }

    @Override
    public void clearNoiseWeightParams(){
        weightNoiseParams.clear();;
    }

    /**
     * Does this layer have no bias term? Many layers (dense, convolutional, output, embedding) have biases by
     * default, but no-bias versions are possible via configuration
     *
     * @return True if a bias term is present, false otherwise
     */
    public boolean hasBias(){
        //Overridden by layers supporting no bias mode: dense, output, convolutional, embedding
        return true;
    }
}
