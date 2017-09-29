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

import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Collection;


/**
 * Output layer with different objective
 * incooccurrences for different objectives.
 * This includes classification as well as prediction
 * @author Adam Gibson
 *
 */
public class OutputLayer extends BaseOutputLayer<org.deeplearning4j.nn.conf.layers.OutputLayer> {

    public OutputLayer(org.deeplearning4j.nn.conf.layers.OutputLayer conf) {
        super(conf);
    }

    @Override
    protected INDArray getLabelsMask2d(INDArray labelMask) {
        return labelMask;
    }


}
