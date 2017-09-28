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

package org.deeplearning4j.nn.graph.vertex.impl.rnn;

import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.graph.vertex.BaseGraphVertex;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.primitives.Pair;

/** LastTimeStepVertex is used in the context of recurrent neural network activations, to go from 3d (time series)
 * activations to 2d activations, by extracting out the last time step of activations for each example.<br>
 * This can be used for example in sequence to sequence architectures, and potentially for sequence classification.
 * <b>NOTE</b>: Because RNNs may have masking arrays (to allow for examples/time series of different lengths in the same
 * minibatch), the last time step will take this into account. If the input to this layer does not have a mask array,
 * the last time step of the input will be used for all examples; otherwise, the time step of the last non-zero entry
 * in the mask array (for each example separately) will be used.
 *
 * @author Alex Black
 */
public class LastTimeStepVertex extends BaseGraphVertex {

    /** Shape of the forward pass activations */
    private int[] fwdPassShape;
    /** Indexes of the time steps that were extracted, for each example */
    private int[] fwdPassTimeSteps;

    public LastTimeStepVertex(String name, int vertexIndex, int numInputs) {
        super(name, vertexIndex, numInputs);
    }

    @Override
    public Activations activate(boolean training) {
        //First: get the mask arrays for the given input, if any
        INDArray mask = input.getMask(0);

        //Then: work out, from the mask array, which time step of activations we want, extract activations
        //Also: record where they came from (so we can do errors later)
        fwdPassShape = input.get(0).shape();

        INDArray out;
        if (mask == null) {
            //No mask array -> extract same (last) column for all
            int lastTS = input.get(0).size(2) - 1;
            out = input.get(0).get(NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(lastTS));
            fwdPassTimeSteps = null; //Null -> last time step for all examples
        } else {
            int[] outShape = new int[] {input.get(0).size(0), input.get(0).size(1)};
            out = Nd4j.create(outShape);

            //Want the index of the last non-zero entry in the mask array.
            //Check a little here by using mulRowVector([0,1,2,3,...]) and argmax
            int maxTsLength = fwdPassShape[2];
            INDArray row = Nd4j.linspace(0, maxTsLength - 1, maxTsLength);
            INDArray temp = mask.mulRowVector(row);
            INDArray lastElementIdx = Nd4j.argMax(temp, 1);
            fwdPassTimeSteps = new int[fwdPassShape[0]];
            for (int i = 0; i < fwdPassTimeSteps.length; i++) {
                fwdPassTimeSteps[i] = (int) lastElementIdx.getDouble(i);
            }

            //Now, get and assign the corresponding subsets of 3d activations:
            for (int i = 0; i < fwdPassTimeSteps.length; i++) {
                out.putRow(i, input.get(0).get(NDArrayIndex.point(i), NDArrayIndex.all(),
                                NDArrayIndex.point(fwdPassTimeSteps[i])));
            }
        }

        return ActivationsFactory.getInstance().create(out);
    }

    @Override
    public Gradients backpropGradient(Gradients gradient) {
        INDArray epsilon = gradient.get(0);
        //Allocate the appropriate sized array:
        INDArray epsilonsOut = Nd4j.create(fwdPassShape);

        if (fwdPassTimeSteps == null) {
            //Last time step for all examples
            epsilonsOut.put(new INDArrayIndex[] {NDArrayIndex.all(), NDArrayIndex.all(),
                            NDArrayIndex.point(fwdPassShape[2] - 1)}, epsilon);
        } else {
            //Different time steps were extracted for each example
            for (int i = 0; i < fwdPassTimeSteps.length; i++) {
                epsilonsOut.put(new INDArrayIndex[] {NDArrayIndex.point(i), NDArrayIndex.all(),
                                NDArrayIndex.point(fwdPassTimeSteps[i])}, epsilon.getRow(i));
            }
        }
        return GradientsFactory.getInstance().create(epsilonsOut, null);
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray backpropGradientsViewArray) {
        if (backpropGradientsViewArray != null)
            throw new RuntimeException("Vertex does not have gradients; gradients view array cannot be set here");
    }

    protected Pair<INDArray, MaskState> feedForwardMaskArrays(INDArray[] maskArrays, MaskState currentMaskState,
                    int minibatchSize) {
        //Input: 2d mask array, for masking a time series. After extracting out the last time step, we no longer need the mask array

        return new Pair<>(null, currentMaskState);
    }

    @Override
    public String toString() {
        return "LastTimeStepVertex()";
    }
}
