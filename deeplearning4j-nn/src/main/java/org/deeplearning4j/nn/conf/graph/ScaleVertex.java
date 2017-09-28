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
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.inputs.InvalidInputTypeException;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.shade.jackson.annotation.JsonProperty;

import java.util.Collection;

/**
 * A ScaleVertex is used to scale the size of activations of a single layer<br>
 * For example, ResNet activations can be scaled in repeating blocks to keep variance
 * under control.
 *
 * @author Justin Long (@crockpotveggies)
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ScaleVertex extends BaseGraphVertex {

    public ScaleVertex(@JsonProperty("scaleFactor") double scaleFactor) {
        this.scaleFactor = scaleFactor;
    }

    protected double scaleFactor;

    @Override
    public ScaleVertex clone() {
        return new ScaleVertex(scaleFactor);
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
    public org.deeplearning4j.nn.api.Layer instantiate(NeuralNetConfiguration conf,
                             Collection<IterationListener> iterationListeners,
                             String name, int layerIndex, int numInputs, INDArray layerParamsView,
                             boolean initializeParams) {

        return new org.deeplearning4j.nn.graph.vertex.impl.ScaleVertex(name, layerIndex, numInputs, scaleFactor);
    }



    @Override
    public InputType[] getOutputType(int layerIndex, InputType... vertexInputs) throws InvalidInputTypeException {
        if (vertexInputs.length == 1)
            return vertexInputs;
        InputType first = vertexInputs[0];

        return new InputType[]{first}; //Same output shape/size as
    }



    @Override
    public LayerMemoryReport getMemoryReport(InputType... inputTypes) {
        //Do one dup on the forward pass (output activations). Accounted for in output activations.
        InputType outputType = getOutputType(-1, inputTypes)[0];
        return new LayerMemoryReport.Builder(null, ScaleVertex.class, inputTypes[0], outputType).standardMemory(0, 0) //No params
                        .workingMemory(0, 0, 0, 0).cacheMemory(0, 0) //No caching
                        .build();
    }
}
