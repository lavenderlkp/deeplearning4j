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
import org.deeplearning4j.nn.graph.vertex.BaseGraphVertex;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.primitives.Pair;

import java.util.Arrays;

/** SubsetVertex is used to select a subset of the activations out of another GraphVertex.<br>
 * For example, a subset of the activations out of a layer.<br>
 * Note that this subset is specifying by means of an interval of the original activations.
 * For example, to get the first 10 activations of a layer (or, first 10 features out of a CNN layer) use
 * new SubsetVertex(0,9).<br>
 * In the case of convolutional (4d) activations, this is done along depth.
 * @author Alex Black
 */
public class SubsetVertex extends BaseGraphVertex {
    private int from;
    private int to; //inclusive
    private int[] forwardShape;

    public SubsetVertex(String name, int vertexIndex, int numInputs, int from, int to) {
        super(name, vertexIndex, numInputs);
        this.from = from;
        this.to = to;
    }

    @Override
    public Activations activate(boolean training) {
        if (input == null || input.anyActivationsNull())
            throw new IllegalStateException("Cannot do forward pass: input not set");

        forwardShape = Arrays.copyOf(input.get(0).shape(), input.get(0).rank());

        INDArray ret;
        switch (input.get(0).rank()) {
            case 2:
                ret = input.get(0).get(NDArrayIndex.all(), NDArrayIndex.interval(from, to, true));
                break;
            case 3:
                ret = input.get(0).get(NDArrayIndex.all(), NDArrayIndex.interval(from, to, true), NDArrayIndex.all());
                break;
            case 4:
                ret = input.get(0).get(NDArrayIndex.all(), NDArrayIndex.interval(from, to, true), NDArrayIndex.all(),
                                NDArrayIndex.all());
                break;
            default:
                throw new UnsupportedOperationException(
                                "Cannot get subset for activations of rank " + input.get(0).rank());
        }

        Pair<INDArray, MaskState> masks = feedForwardMaskArrays(new INDArray[]{input.getMask(0)}, MaskState.Active, getInputMiniBatchSize());
        return ActivationsFactory.getInstance().create(ret, masks.getFirst(), masks.getSecond());
    }

    @Override
    public Gradients backpropGradient(Gradients gradient) {
        if (gradient == null || gradient.get(0) == null)
            throw new IllegalStateException("Cannot do backward pass: activation gradients not available (null)");
        INDArray epsilon = gradient.get(0);

        INDArray out = Nd4j.zeros(forwardShape);
        switch (forwardShape.length) {
            case 2:
                out.put(new INDArrayIndex[] {NDArrayIndex.all(), NDArrayIndex.interval(from, to, true)}, epsilon);
                break;
            case 3:
                out.put(new INDArrayIndex[] {NDArrayIndex.all(), NDArrayIndex.interval(from, to, true),
                                NDArrayIndex.all()}, epsilon);
                break;
            case 4:
                out.put(new INDArrayIndex[] {NDArrayIndex.all(), NDArrayIndex.interval(from, to, true),
                                NDArrayIndex.all(), NDArrayIndex.all()}, epsilon);
                break;
            default:
                throw new RuntimeException("Invalid activation rank"); //Should never happen
        }
        return GradientsFactory.getInstance().create(out, null);
    }

    @Override
    public String toString() {
        return "SubsetVertex(id=" + this.getIndex() + ",name=\"" + this.getName() + "\",fromIdx=" + from
                        + ",toIdx=" + to + ")";
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray backpropGradientsViewArray) {
        if (backpropGradientsViewArray != null)
            throw new RuntimeException("Vertex does not have gradients; gradients view array cannot be set here");
    }


    protected Pair<INDArray, MaskState> feedForwardMaskArrays(INDArray[] maskArrays, MaskState currentMaskState,
                    int minibatchSize) {
        //No op: subset just provides part of the activations for each example (or time step)
        if (maskArrays == null || maskArrays.length == 0) {
            return null;
        }

        return new Pair<>(maskArrays[0], currentMaskState);
    }
}
