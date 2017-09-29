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

import lombok.Getter;
import lombok.Setter;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.api.layers.IOutputLayer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.optimize.Solver;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.activations.impl.ActivationIdentity;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.nd4j.linalg.primitives.Pair;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;


/**
 * Output layer with different objective
 * in co-occurrences for different objectives.
 * This includes classification as well as prediction
 * @author Adam Gibson
 *
 */
public abstract class BaseOutputLayer<LayerConfT extends org.deeplearning4j.nn.conf.layers.BaseOutputLayer>
                extends BaseLayer<LayerConfT> implements Serializable, IOutputLayer {
    private static final IActivation IDENTITY = new ActivationIdentity();

    //current input and label matrices
    protected INDArray labels;
    @Getter
    protected INDArray labelMask;

    private transient Solver solver;

    protected double score;

    public BaseOutputLayer(org.deeplearning4j.nn.conf.layers.BaseOutputLayer conf) {
        super(conf);
    }

    @Override
    public double score(){
        return score;
    }

    /** Compute score after labels and input have been set.
     * @param fullNetworkL1 L1 regularization term for the entire network
     * @param fullNetworkL2 L2 regularization term for the entire network
     * @param training whether score should be calculated at train or test time (this affects things like application of
     *                 dropout, etc)
     * @return score (loss function)
     */
    @Override
    public double computeScore(Activations layerOutput, Activations labels, double fullNetworkL1, double fullNetworkL2, boolean training) {
        if (layerOutput == null || layerOutput.anyActivationsNull() || labels == null)
            throw new IllegalStateException("Cannot calculate score without layer output and labels " + layerId());
        if(layerOutput.size() != 1)
            throw new IllegalStateException("Excepted output activations of size 1. Got size: " + layerOutput.size());

        ILossFunction lossFunction = layerConf().getLossFn();

        //Note: (preOutput + activation function) == (output + identity), from the perspective of scoring
        INDArray labels2d = getLabels2d(labels.get(0));
        INDArray out2d = getLabels2d(layerOutput.get(0));    //Labels and output have same shape -> same reshape op required
        double score = lossFunction.computeScore(labels2d, out2d, IDENTITY, getLabelsMask2d(labels.getMask(0)),
                        false);
        score += fullNetworkL1 + fullNetworkL2;
        score /= labels.get(0).size(0); //Divide by minibatch size

        this.score = score;
        return score;
    }

    /**Compute the score for each example individually, after labels and input have been set.
     *
     * @param fullNetworkL1 L1 regularization term for the entire network (or, 0.0 to not include regularization)
     * @param fullNetworkL2 L2 regularization term for the entire network (or, 0.0 to not include regularization)
     * @return A column INDArray of shape [numExamples,1], where entry i is the score of the ith example
     */
    @Override
    public INDArray computeScoreForExamples(Activations layerOutput, Activations labels, double fullNetworkL1, double fullNetworkL2) {
        if (layerOutput == null || layerOutput.anyActivationsNull() || labels == null)
            throw new IllegalStateException("Cannot calculate score without layer output and labels " + layerId()
                    + (layerOutput == null ? " - layour output null" : "" ) + (labels == null ? " - labels null" : ""));

        ILossFunction lossFunction = layerConf().getLossFn();
        //Note: (preOutput + activation function) == (output + identity), from the perspective of scoring
        INDArray scoreArray =
                        lossFunction.computeScoreArray(getLabels2d(labels.get(0)), layerOutput.get(0), IDENTITY,
                                getLabelsMask2d(labels.getMask(0)));
        double l1l2 = fullNetworkL1 + fullNetworkL2;
        if (l1l2 != 0.0) {
            scoreArray.addi(l1l2);
        }
        return scoreArray;
    }

    @Override
    public Gradients backpropGradient(Gradients epsilons) {
        if(input == null || input.anyActivationsNull()){
            throw new IllegalStateException("Cannot compute backprop gradient: input is null/not set");
        }

        Gradients pair = getGradientsAndDelta(preOutput2d(true)); //Returns Gradient and delta^(this), not Gradient and epsilon^(this-1)
        INDArray delta = pair.get(0);

        INDArray epsilonNext = getParamWithNoise(DefaultParamInitializer.WEIGHT_KEY, true).mmul(delta.transpose()).transpose();

        //Normally we would clear weightNoiseParams here - but we want to reuse them for forward + backward + score
        // So this is instead done in MultiLayerNetwork/CompGraph backprop methods

        Gradients g = GradientsFactory.getInstance().create(epsilonNext, pair.getParameterGradients());
        return backpropPreprocessor(g);
    }

    /** Returns tuple: {Gradient,Delta,Output} given preOut */
    private Gradients getGradientsAndDelta(INDArray preOut) {
        ILossFunction lossFunction = layerConf().getLossFn();
        INDArray labels2d = getLabels2d();
        INDArray delta = lossFunction.computeGradient(labels2d, preOut, layerConf().getActivationFn(), getLabelsMask2d(labelMask));

        Gradient gradient = new DefaultGradient();

        INDArray weightGradView = gradientViews.get(DefaultParamInitializer.WEIGHT_KEY);
        Nd4j.gemm(input.get(0), delta, weightGradView, true, false, 1.0, 0.0); //Equivalent to:  weightGradView.assign(input.transpose().mmul(delta));
        gradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, weightGradView);

        if(hasBias()){
            INDArray biasGradView = gradientViews.get(DefaultParamInitializer.BIAS_KEY);
            delta.sum(biasGradView, 0); //biasGradView is initialized/zeroed first in sum op
            gradient.gradientForVariable().put(DefaultParamInitializer.BIAS_KEY, biasGradView);
        }

        return GradientsFactory.getInstance().create(gradient, delta);
    }


    @Override
    public Activations activate(Activations input, boolean training) {
        if(input.size() != 1)
            throw new IllegalStateException();
        setInput(input);
        return output(training);
    }

    public Activations output(Activations input, boolean training) {
        setInput(input);
        return output(training);
    }

    /**
     * Classify input
     * @param training determines if its training
     * the input (can either be a matrix or vector)
     * If it's a matrix, each row is considered an example
     * and associated rows are classified accordingly.
     * Each row will be the likelihood of a label given that example
     * @return a probability distribution for each row
     */
    public Activations output(boolean training) {
        if (input == null) {
            throw new IllegalArgumentException("Cannot perform forward pass with null input - " + layerId());
        }
        return super.activate(training);
    }

    @Override
    public void clear() {
        super.clear();
        labels = null;
        solver = null;
    }

    @Override
    public INDArray getLabels() {
        return labels;
    }

    @Override
    public void setLabels(INDArray labels, INDArray labelsMask) {
        this.labels = labels;
        this.labelMask = labelsMask;
    }

    @Override
    public void setLabels(Activations labels){
        if(labels == null){
            setLabels(null, null);
        } else {
            if(labels.size() != 1){
                throw new IllegalArgumentException("Cannot set labels: must be of size (# arrays) 1. Got labels size: " + labels.size());
            }
            setLabels(labels.get(0), labels.getMask(0));
        }
    }

    protected INDArray preOutput2d(boolean training) {
        return preOutput(training);
    }

    @Override
    protected void applyMask(INDArray to) {
        INDArray maskArray = getLabelsMask2d(labelMask);
        if (maskArray == null) {
            return;
        }

        //For output layers: can be either per-example masking, or per-output masking
        if (maskArray.isColumnVector()) {
            to.muliColumnVector(maskArray);
        } else if (Arrays.equals(to.shape(), maskArray.shape())) {
            to.muli(maskArray);
        } else {
            throw new IllegalStateException("Invalid mask array: per-example masking should be a column vector, "
                            + "per output masking arrays should be the same shape as the output/labels arrays. Mask shape: "
                            + Arrays.toString(maskArray.shape()) + ", output shape: " + Arrays.toString(to.shape())
                            + layerId());
        }
    }


    protected INDArray getLabels2d() {
        return getLabels2d(labels);
    }

    protected INDArray getLabels2d(INDArray labels){
        if (labels != null && labels.rank() > 2) {
            return labels.reshape(labels.size(2), labels.size(1));
        }
        return labels;
    }

    //Issue: labels mask may need to be reshaped
    protected abstract INDArray getLabelsMask2d(INDArray labelsMask);

    @Override
    public boolean isPretrainLayer() {
        return false;
    }

    @Override
    public boolean hasBias() {
        return layerConf().hasBias();
    }
}
