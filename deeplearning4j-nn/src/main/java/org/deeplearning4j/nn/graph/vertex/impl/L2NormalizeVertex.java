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
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastDivOp;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastMulOp;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.primitives.Pair;

/**
 * L2NormalizeVertex performs L2 normalization on a single input.
 *
 * @author Justin Long (crockpotveggies)
 * @author Alex Black (AlexDBlack)
 */
public class L2NormalizeVertex extends BaseGraphVertex {

    private static final int[] DEFAULT_RANK2_DIMS = new int[] {1};
    private static final int[] DEFAULT_RANK3_DIMS = new int[] {1, 2};
    private static final int[] DEFAULT_RANK4_DIMS = new int[] {1, 2, 3};

    private int[] dimension;
    private double eps;

    public L2NormalizeVertex(String name, int vertexIndex, int numInputs, int[] dimension, double eps) {
        super(name, vertexIndex, numInputs);
        this.dimension = dimension;
        this.eps = eps;
    }

    @Override
    public Activations activate(boolean training) {
        if (input == null || input.anyActivationsNull())
            throw new IllegalStateException("Cannot do forward pass: inputs not set (L2NormalizeVertex " + vertexName
                            + " idx " + getIndex() + ")");

        // L2 norm along all dimensions except 0, unless user-specified
        // x / |x|2
        INDArray x = input.get(0);
        int[] dimensions = getDimensions(x);

        INDArray xNorm2 = x.norm2(dimensions);
        Transforms.max(xNorm2, eps, false);

        Pair<INDArray, MaskState> masks = feedForwardMaskArrays(new INDArray[]{input.getMask(0)}, MaskState.Active, getInputMiniBatchSize());
        if (x.rank() == 2) {
            return ActivationsFactory.getInstance().create(x.divColumnVector(xNorm2));
        } else {
            INDArray out = Nd4j.createUninitialized(x.shape(), x.ordering());
            return ActivationsFactory.getInstance().create(
                    Nd4j.getExecutioner().execAndReturn(new BroadcastDivOp(x, xNorm2, out, 0)),
                            masks.getFirst(), masks.getSecond());
        }
    }

    @Override
    public Gradients backpropGradient(Gradients gradient) {
        if (gradient == null || gradient.get(0) == null)
            throw new IllegalStateException("Cannot do backward pass: activation gradients not available (null) " + layerId());
        INDArray epsilon = gradient.get(0);

        INDArray x = input.get(0);
        int[] dimensions = getDimensions(x);

        INDArray norm = x.norm2(dimensions);
        INDArray norm3 = Transforms.pow(norm, 3.0, true);
        Transforms.max(norm, eps, false); // in case of div/0
        Transforms.max(norm3, eps, false);

        INDArray dLdx;
        if (x.rank() == 2) {
            // 2D case
            dLdx = epsilon.divColumnVector(norm);
            INDArray xDivNorm3 = x.divColumnVector(norm3);
            dLdx.subi(xDivNorm3.muliColumnVector(epsilon.mul(x).sum(1)));
        } else {
            //RNN and CNN case - Broadcast along dimension 0
            INDArray dx = epsilon.mul(x).sum(dimensions);

            //x / |x|_2^3 * sum_k (dLda*x)
            INDArray xDivNorm3 = Nd4j.createUninitialized(x.shape(), x.ordering());
            Nd4j.getExecutioner().exec(new BroadcastDivOp(x, norm3, xDivNorm3, 0));
            Nd4j.getExecutioner().exec(new BroadcastMulOp(xDivNorm3, dx, xDivNorm3, 0));

            //1/|x|_2 * dLda - above
            dLdx = Nd4j.createUninitialized(epsilon.shape(), epsilon.ordering());
            Nd4j.getExecutioner().exec(new BroadcastDivOp(epsilon, norm, dLdx, 0));
            dLdx.subi(xDivNorm3);
        }

        return GradientsFactory.getInstance().create(dLdx, null);
    }

    private int[] getDimensions(INDArray x) {
        if (dimension == null || dimension.length < 1) {
            switch (x.rank()) {
                case 2:
                    return DEFAULT_RANK2_DIMS;
                case 3:
                    return DEFAULT_RANK3_DIMS;
                case 4:
                    return DEFAULT_RANK4_DIMS;
                default:
                    throw new RuntimeException();
            }
        }
        return dimension;
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray backpropGradientsViewArray) {
        if (backpropGradientsViewArray != null)
            throw new RuntimeException("Vertex does not have gradients; gradients view array cannot be set here");
    }


    protected Pair<INDArray, MaskState> feedForwardMaskArrays(INDArray[] maskArrays, MaskState currentMaskState,
                    int minibatchSize) {
        //No op
        if (maskArrays == null || maskArrays.length == 0) {
            return null;
        }

        return new Pair<>(maskArrays[0], currentMaskState);
    }

    @Override
    public String toString() {
        return "L2NormalizeVertex(id=" + this.getIndex() + ",name=\"" + this.getName() + ",dim=\""
                        + dimension + "\")";
    }
}
