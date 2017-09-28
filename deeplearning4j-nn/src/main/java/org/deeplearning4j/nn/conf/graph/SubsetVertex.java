/*-
 *
 *  * Copyright 2016 Skymind,Inc.
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

package org.deeplearning4j.nn.conf.graph;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.inputs.InvalidInputTypeException;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.nn.conf.memory.MemoryReport;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.shade.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.Collection;

/**
 * SubsetVertex is used to select a subset of the activations out of another GraphVertex.<br>
 * For example, a subset of the activations out of a layer.<br>
 * Note that this subset is specifying by means of an interval of the original activations.
 * For example, to get the first 10 activations of a layer (or, first 10 features out of a CNN layer) use
 * new SubsetVertex(0,9).<br>
 * In the case of convolutional (4d) activations, this is done along depth.
 *
 * @author Alex Black
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class SubsetVertex extends GraphVertex {

    private int from;
    private int to;

    /**
     * @param from The first column index, inclusive
     * @param to   The last column index, inclusive
     */
    public SubsetVertex(@JsonProperty("from") int from, @JsonProperty("to") int to) {
        this.from = from;
        this.to = to;
    }

    @Override
    public SubsetVertex clone() {
        return new SubsetVertex(from, to);
    }

    @Override
    public int numParams(boolean backprop) {
        return 0;
    }

    @Override
    public int minInputs() {
        return 1;
    }

    @Override
    public int maxInputs() {
        return 1;
    }

    @Override
    public Layer instantiate(NeuralNetConfiguration conf,
                             Collection<IterationListener> iterationListeners,
                             String name, int idx, int numInputs, INDArray layerParamsView,
                             boolean initializeParams) {
        return new org.deeplearning4j.nn.graph.vertex.impl.SubsetVertex(name, idx, numInputs, from, to);
    }

    @Override
    public InputType[] getOutputType(int layerIndex, InputType... vertexInputs) throws InvalidInputTypeException {
        if (vertexInputs.length != 1) {
            throw new InvalidInputTypeException(
                            "SubsetVertex expects single input type. Received: " + Arrays.toString(vertexInputs));
        }

        InputType ret;
        switch (vertexInputs[0].getType()) {
            case FF:
                ret = InputType.feedForward(to - from + 1);
                break;
            case RNN:
                ret = InputType.recurrent(to - from + 1);
                break;
            case CNN:
                InputType.InputTypeConvolutional conv = (InputType.InputTypeConvolutional) vertexInputs[0];
                int depth = conv.getDepth();
                if (to >= depth) {
                    throw new InvalidInputTypeException("Invalid range: Cannot select depth subset [" + from + "," + to
                                    + "] inclusive from CNN activations with " + " [depth,width,height] = [" + depth
                                    + "," + conv.getWidth() + "," + conv.getHeight() + "]");
                }
                ret = InputType.convolutional(conv.getHeight(), conv.getWidth(), from - to + 1);
                break;
            case CNNFlat:
                //TODO work out how to do this - could be difficult...
                throw new UnsupportedOperationException(
                                "Subsetting data in flattened convolutional format not yet supported");
            default:
                throw new RuntimeException("Unknown input type: " + vertexInputs[0]);
        }
        return new InputType[]{ret};
    }

    @Override
    public LayerMemoryReport getMemoryReport(InputType... inputTypes) {
        //Get op without dup - no additional memory use
        InputType outputType = getOutputType(-1, inputTypes)[0];
        return new LayerMemoryReport.Builder(null, SubsetVertex.class, inputTypes[0], outputType).standardMemory(0, 0) //No params
                        .workingMemory(0, 0, 0, 0).cacheMemory(0, 0) //No caching
                        .build();
    }
}
