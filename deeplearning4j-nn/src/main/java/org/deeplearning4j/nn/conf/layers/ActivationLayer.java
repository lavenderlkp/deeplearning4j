package org.deeplearning4j.nn.conf.layers;

import lombok.*;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.ParamInitializer;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.nn.conf.memory.MemoryReport;
import org.deeplearning4j.nn.params.EmptyParamInitializer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ActivationLayer extends org.deeplearning4j.nn.conf.layers.Layer {

    protected IActivation activationFn;

    protected ActivationLayer(Builder builder) {
        super(builder);
        this.activationFn = builder.activationFn;
        initializeConstraints(builder.allParamConstraints, builder.weightConstraints, builder.biasConstraints);
    }

    @Override
    public ActivationLayer clone() {
        ActivationLayer clone = (ActivationLayer) super.clone();
        return clone;
    }

    @Override
    public Layer instantiate(Collection<IterationListener> iterationListeners,
                             String name, int layerIndex, int numInputs, INDArray layerParamsView,
                             boolean initializeParams) {
        org.deeplearning4j.nn.layers.ActivationLayer ret = new org.deeplearning4j.nn.layers.ActivationLayer(this);
        ret.setIndex(layerIndex);
        ret.setParamsViewArray(layerParamsView);
        Map<String, INDArray> paramTable = initializer().init(this, layerParamsView, initializeParams);
        ret.setParamTable(paramTable);
        ret.setConf(this);
        return ret;
    }

    @Override
    public ParamInitializer initializer() {
        return EmptyParamInitializer.getInstance();
    }

    @Override
    public InputType[] getOutputType(int layerIndex, InputType... inputType) {
        if (inputType == null)
            throw new IllegalStateException("Invalid input type: null for layer name \"" + getLayerName() + "\"");
        if (preProcessor != null) {
            inputType = preProcessor.getOutputType(inputType);
        }
        return inputType;
    }

    @Override
    public InputPreProcessor getPreProcessorForInputType(InputType inputType) {
        //No input preprocessor required for any input
        return null;
    }

    @Override
    public double getL1ByParam(String paramName) {
        //Not applicable
        return 0;
    }

    @Override
    public double getL2ByParam(String paramName) {
        //Not applicable
        return 0;
    }

    @Override
    public boolean isPretrainParam(String paramName) {
        throw new UnsupportedOperationException("Activation layer does not contain parameters");
    }

    @Override
    public LayerMemoryReport getMemoryReport(InputType... inputTypes) {
        if(inputTypes == null || inputTypes.length != 1){
            throw new IllegalArgumentException("Expected 1 input type: got " + (inputTypes == null ? null : Arrays.toString(inputTypes)));
        }
        InputType inputType = inputTypes[0];

        int actElementsPerEx = inputType.arrayElementsPerExample();

        return new LayerMemoryReport.Builder(layerName, ActivationLayer.class, inputType, inputType)
                        .standardMemory(0, 0) //No params
                        //During inference: modify input activation in-place
                        //During  backprop: dup the input for later re-use
                        .workingMemory(0, 0, 0, actElementsPerEx)
                        .cacheMemory(MemoryReport.CACHE_MODE_ALL_ZEROS, MemoryReport.CACHE_MODE_ALL_ZEROS) //No caching
                        .build();
    }

    @Override
    public void setNIn(InputType[] inputType, boolean override) {
        //No op
    }

    @AllArgsConstructor
    @NoArgsConstructor
    public static class Builder extends org.deeplearning4j.nn.conf.layers.Layer.Builder<Builder> {

        private IActivation activationFn = null;

        /**
         * Layer activation function.
         * Typical values include:<br>
         * "relu" (rectified linear), "tanh", "sigmoid", "softmax",
         * "hardtanh", "leakyrelu", "maxout", "softsign", "softplus"
         * @deprecated Use {@link #activation(Activation)} or {@link @activation(IActivation)}
         */
        @Deprecated
        public Builder activation(String activationFunction) {
            return activation(Activation.fromString(activationFunction));
        }

        public Builder activation(IActivation activationFunction) {
            this.activationFn = activationFunction;
            return this;
        }

        public Builder activation(Activation activation) {
            return activation(activation.getActivationFunction());
        }

        @Override
        @SuppressWarnings("unchecked")
        public ActivationLayer build() {
            return new ActivationLayer(this);
        }
    }
}
