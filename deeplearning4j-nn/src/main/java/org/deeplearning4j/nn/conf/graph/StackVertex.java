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


import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.inputs.InvalidInputTypeException;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.nn.conf.memory.MemoryReport;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Collection;

/**
 * StackVertex allows for stacking of inputs so that they may be forwarded through
 * a network. This is useful for cases such as Triplet Embedding, where shared parameters
 * are not supported by the network.
 *
 * @author Justin Long (crockpotveggies)
 */
public class StackVertex extends BaseGraphVertex {

    public StackVertex() {}

    @Override
    public StackVertex clone() {
        return new StackVertex();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StackVertex;
    }

    @Override
    public int minInputs() {
        return 1;
    }

    @Override
    public int maxInputs() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int hashCode() {
        return 433682566;
    }

    @Override
    public Layer instantiate(NeuralNetConfiguration conf,
                             Collection<IterationListener> iterationListeners,
                             String name, int idx, int numInputs, INDArray layerParamsView,
                             boolean initializeParams) {
        return new org.deeplearning4j.nn.graph.vertex.impl.StackVertex(name, idx, numInputs);
    }

    @Override
    public String toString() {
        return "StackVertex()";
    }

    @Override
    public InputType[] getOutputType(int layerIndex, InputType... vertexInputs) throws InvalidInputTypeException {
        if (vertexInputs.length == 1)
            return vertexInputs;
        InputType first = vertexInputs[0];
        if (first.getType() == InputType.Type.CNNFlat) {
            //TODO
            //Merging flattened CNN format data could be messy?
            throw new InvalidInputTypeException(
                            "Invalid input: StackVertex cannot currently merge CNN data in flattened format. Got: "
                                            + vertexInputs);
        } else if (first.getType() != InputType.Type.CNN) {
            //FF or RNN data inputs
            int size = 0;
            int tsLength = -1;
            InputType.Type type = null;
            for (int i = 0; i < vertexInputs.length; i++) {
                if (vertexInputs[i].getType() != first.getType()) {
                    throw new InvalidInputTypeException(
                                    "Invalid input: StackVertex cannot merge activations of different types:"
                                                    + " first type = " + first.getType() + ", input type " + (i + 1)
                                                    + " = " + vertexInputs[i].getType());
                }

                int thisSize;
                switch (vertexInputs[i].getType()) {
                    case FF:
                        thisSize = ((InputType.InputTypeFeedForward) vertexInputs[i]).getSize();
                        type = InputType.Type.FF;
                        break;
                    case RNN:
                        thisSize = ((InputType.InputTypeRecurrent) vertexInputs[i]).getSize();
                        tsLength = ((InputType.InputTypeRecurrent) vertexInputs[i]).getTimeSeriesLength();
                        type = InputType.Type.RNN;
                        break;
                    default:
                        throw new IllegalStateException("Unknown input type: " + vertexInputs[i]); //Should never happen
                }
                if (thisSize <= 0) {//Size is not defined
                    size = -1;
                } else {
                    size += thisSize;
                }
            }

            InputType ret;
            if (size > 0) {
                //Size is specified
                if (type == InputType.Type.FF)
                    ret = InputType.feedForward(size);
                else
                    ret = InputType.recurrent(size, tsLength);
            } else {
                //size is unknown
                if (type == InputType.Type.FF)
                    ret = InputType.feedForward(-1);
                else
                    ret = InputType.recurrent(-1, tsLength);
            }
            return new InputType[]{ret};
        } else {
            //CNN inputs... also check that the depth, width and heights match:
            InputType.InputTypeConvolutional firstConv = (InputType.InputTypeConvolutional) first;
            int fd = firstConv.getDepth();
            int fw = firstConv.getWidth();
            int fh = firstConv.getHeight();

            int depthSum = fd;

            for (int i = 1; i < vertexInputs.length; i++) {
                if (vertexInputs[i].getType() != InputType.Type.CNN) {
                    throw new InvalidInputTypeException(
                                    "Invalid input: StackVertex cannot process activations of different types:"
                                                    + " first type = " + InputType.Type.CNN + ", input type " + (i + 1)
                                                    + " = " + vertexInputs[i].getType());
                }

                InputType.InputTypeConvolutional otherConv = (InputType.InputTypeConvolutional) vertexInputs[i];

                int od = otherConv.getDepth();
                int ow = otherConv.getWidth();
                int oh = otherConv.getHeight();

                if (fw != ow || fh != oh) {
                    throw new InvalidInputTypeException(
                                    "Invalid input: StackVertex cannot merge CNN activations of different width/heights:"
                                                    + "first [depth,width,height] = [" + fd + "," + fw + "," + fh
                                                    + "], input " + i + " = [" + od + "," + ow + "," + oh + "]");
                }

                depthSum += od;
            }

            return new InputType[]{InputType.convolutional(fh, fw, depthSum)};
        }
    }

    @Override
    public LayerMemoryReport getMemoryReport(InputType... inputTypes) {
        //No working memory, just output activations
        InputType outputType = getOutputType(-1, inputTypes)[0];

        return new LayerMemoryReport.Builder(null, StackVertex.class, inputTypes[0], outputType).standardMemory(0, 0) //No params
                        .workingMemory(0, 0, 0, 0).cacheMemory(0, 0) //No caching
                        .build();
    }
}
