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

package org.deeplearning4j.nn.graph.vertex.impl;

import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.vertex.BaseGraphVertex;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.transforms.Or;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.primitives.Pair;

import java.util.Arrays;

/** A MergeVertex is used to combine the activations of two or more layers/GraphVertex by means of concatenation/merging.<br>
 * Exactly how this is done depends on the type of input.<br>
 * For 2d (feed forward layer) inputs: MergeVertex([numExamples,layerSize1],[numExamples,layerSize2]) -> [numExamples,layerSize1 + layerSize2]<br>
 * For 3d (time series) inputs: MergeVertex([numExamples,layerSize1,timeSeriesLength],[numExamples,layerSize2,timeSeriesLength])
 *      -> [numExamples,layerSize1 + layerSize2,timeSeriesLength]<br>
 * For 4d (convolutional) inputs: MergeVertex([numExamples,depth1,width,height],[numExamples,depth2,width,height])
 *      -> [numExamples,depth1 + depth2,width,height]<br>
 * @author Alex Black
 */
public class MergeVertex extends BaseGraphVertex {

    private int[][] forwardPassShapes;
    private int fwdPassRank;

    public MergeVertex(String name, int vertexIndex, int numInputs) {
        super(name, vertexIndex, numInputs);
    }

    @Override
    public String toString() {
        return "MergeVertex(id=" + this.getIndex() + ",name=\"" + this.getName() + "\")";
    }

    @Override
    public Activations activate(boolean training) {
        if (input == null || input.anyActivationsNull())
            throw new IllegalStateException("Cannot do forward pass: inputs not set");

        if (input.size() == 1) {
            //No-op case
            int[] shape = input.get(0).shape();
            forwardPassShapes = new int[][] {Arrays.copyOf(shape, shape.length)};
            return input;
        }

        forwardPassShapes = new int[input.size()][0];
        int nExamples = input.get(0).size(0);
        int nOut = 0;
        fwdPassRank = input.get(0).rank();
        for (int i = 0; i < input.size(); i++) {
            int[] currShape = input.get(i).shape();
            if (fwdPassRank != currShape.length) {
                throw new IllegalStateException(
                                "Cannot merge activations with different ranks: first activations have rank "
                                                + fwdPassRank + ", activations[" + i + "] have rank " + currShape.length
                                                + " (shape=" + Arrays.toString(currShape) + ")");
            }
            forwardPassShapes[i] = Arrays.copyOf(currShape, currShape.length);
            if (currShape[0] != nExamples) {
                throw new IllegalStateException(
                                "Cannot merge activations with different number of examples (activations[0] shape: "
                                                + Arrays.toString(input.get(0).shape()) + ", activations[" + i
                                                + "] shape: " + Arrays.toString(input.get(i).shape()));
            }

            nOut += currShape[1]; //Same dimension for all of CNNs, FF, RNNs
        }

        INDArray out = Nd4j.hstack(input.getAsArray());

        Pair<INDArray, MaskState> masks = feedForwardMaskArrays(new INDArray[]{input.getMask(0)}, MaskState.Active, getInputMiniBatchSize());
        return ActivationsFactory.getInstance().create(out, masks.getFirst(), masks.getSecond());
    }

    @Override
    public Gradients backpropGradient(Gradients gradient) {
        if (gradient == null || gradient.get(0) == null)
            throw new IllegalStateException("Cannot do backward pass: activation gradients not available (null)");
        INDArray epsilon = gradient.get(0);

        if (forwardPassShapes.length == 1) {
            return gradient;
        }

        //Split the epsilons in the opposite way that the activations were merged
        INDArray[] out = new INDArray[forwardPassShapes.length];
        for (int i = 0; i < out.length; i++)
            out[i] = Nd4j.createUninitialized(forwardPassShapes[i]);

        int cumulative = 0;
        switch (fwdPassRank) {
            case 2:
                //Standard
                for (int i = 0; i < forwardPassShapes.length; i++) {
                    out[i].assign(epsilon.get(NDArrayIndex.all(), //All rows
                                    NDArrayIndex.interval(cumulative, cumulative + forwardPassShapes[i][1]))); //subset of columns
                    cumulative += forwardPassShapes[i][1];
                }
                break;
            case 3:
                for (int i = 0; i < forwardPassShapes.length; i++) {
                    out[i].assign(epsilon.get(NDArrayIndex.all(), //All rows
                                    NDArrayIndex.interval(cumulative, cumulative + forwardPassShapes[i][1]), //subset of columns
                                    NDArrayIndex.all())); //All time steps

                    cumulative += forwardPassShapes[i][1];
                }
                break;
            case 4:
                for (int i = 0; i < forwardPassShapes.length; i++) {
                    out[i].assign(epsilon.get(NDArrayIndex.all(),
                                    NDArrayIndex.interval(cumulative, cumulative + forwardPassShapes[i][1]), //Subset of depth
                                    NDArrayIndex.all(), //Width
                                    NDArrayIndex.all())); //height
                    cumulative += forwardPassShapes[i][1];
                }
                break;
            default:
                throw new RuntimeException("Invalid rank during forward pass (not 2, 3, 4)"); //Should never happen
        }

        return GradientsFactory.getInstance().create(null, out);
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray backpropGradientsViewArray) {
        if (backpropGradientsViewArray != null)
            throw new RuntimeException("Vertex does not have gradients; gradients view array cannot be set here");
    }


    protected Pair<INDArray, MaskState> feedForwardMaskArrays(INDArray[] maskArrays, MaskState currentMaskState,
                    int minibatchSize) {
        if (maskArrays == null) {
            return new Pair<>(null, currentMaskState);
        }

        //Most common case: all or none.
        //If there's only *some* mask arrays: assume the others (missing) are equivalent to all 1s
        //And for handling multiple masks: best strategy seems to be an OR operation
        //i.e., output is 1 if any of the input are 1s
        //Which means: if any masks are missing, output null (equivalent to no mask)
        //Otherwise do an element-wise OR operation

        for (INDArray arr : maskArrays) {
            if (arr == null) {
                return new Pair<>(null, currentMaskState);
            }
        }

        //At this point: all present. Do OR operation
        if (maskArrays.length == 1) {
            return new Pair<>(maskArrays[0], currentMaskState);
        } else {
            INDArray ret = maskArrays[0].dup(maskArrays[0].ordering());
            Nd4j.getExecutioner().exec(new Or(maskArrays[0], maskArrays[1], ret));
            for (int i = 2; i < maskArrays.length; i++) {
                Nd4j.getExecutioner().exec(new Or(maskArrays[i], ret, ret));
            }
            return new Pair<>(ret, currentMaskState);
        }
    }
}
