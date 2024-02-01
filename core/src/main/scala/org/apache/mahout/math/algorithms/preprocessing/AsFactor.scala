/**
  * Licensed to the Apache Software Foundation (ASF) under one
  * or more contributor license agreements. See the NOTICE file
  * distributed with this work for additional information
  * regarding copyright ownership. The ASF licenses this file
  * to you under the Apache License, Version 2.0 (the
  * "License"); you may not use this file except in compliance
  * with the License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  * KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations
  * under the License.
  */

package org.apache.mahout.math.algorithms.preprocessing

import collection._
import JavaConversions._
import org.apache.mahout.math._
import org.apache.mahout.math.drm._
import org.apache.mahout.math.{Vector => MahoutVector}
import org.apache.mahout.math.drm.RLikeDrmOps._
import org.apache.mahout.math.scalabindings._
import org.apache.mahout.math.scalabindings.RLikeOps._
import MahoutCollections._

/**
  * AsFactor is a preprocessor that turns a column matrix of integers into a matrix of one-hot encoded row vectors.
**/
class AsFactor extends PreprocessorFitter {

  /**
    * Fit the model to the data
    *
    * @param input
    * @param hyperparameters
    * @return a fitted AsFactorModel
    */
  def fit[K](input: DrmLike[K],
             hyperparameters: (Symbol, Any)*): AsFactorModel = {

    import org.apache.mahout.math.function.VectorFunction
    val factorMap = input.allreduceBlock(
      { case (keys, block: Matrix) => block },
      { case (oldM: Matrix, newM: Matrix) =>
        // someday we'll replace this with block.max: Vector
        // or better yet- block.distinct

        dense((oldM rbind newM).aggregateColumns( new VectorFunction {
          def apply(f: Vector): Double = f.max
        }))
      })(0, ::)
    /*
    val A = drmParallelize(dense(
      (3, 2, 1),
      (0, 0, 0),
      (1, 1, 1))
      -> (4,2,2),  now 4,3,2
     */
    new AsFactorModel(factorMap.sum.toInt,
      dvec(factorMap.toArray.scanLeft(0.0)((l, r) => l + r ).take(factorMap.length))
    //  factorMap
    )
  }

}

/**
  * AsFactorModel is a model that turns a vector of integers into a vector of one-hot encoded
  * vectors.
  *
  * For example a matrix:
  * (0)
  * (1)
  * (0)
  * (5)
  * (7)
  *
  * Would be transformed into the matrix:
  * (1, 0, 0, 0)
  * (0, 1, 0, 0)
  * (1, 0, 0, 0)
  * (0, 0, 1, 0)
  * (0, 0, 0, 1)
  *
  * NOTE: There is no correlation between the integer input and what column it is mapped to.
  *
  * @param cardinality number of features in the input vector
  * @param factorVec
  */
class AsFactorModel(cardinality: Int, factorVec: MahoutVector) extends PreprocessorModel {

  val factorMap: MahoutVector = factorVec

   /**
    * Transform the input data - ie one-hot encode it
    *
    * @param input DrmLike[K]
    * @return a one-hot encoded DrmLike[K]
    */
  def transform[K](input: DrmLike[K]): DrmLike[K] ={

    implicit val ctx = input.context

    val bcastK = drmBroadcast(dvec(cardinality))
    val bcastFactorMap = drmBroadcast(factorMap)

    implicit val ktag =  input.keyClassTag

    val res = input.mapBlock(cardinality) {
      case (keys, block: Matrix) => {
        val cardinality: Int = bcastK.value.get(0).toInt
        val output = new SparseMatrix(block.nrow, cardinality)
        // This is how we take a vector of mapping to a map
        val fm = bcastFactorMap.value
        for (n <- 0 until output.nrow){
          var m = 0
          for (e <- block(n, ::).all() ){
            output(n, fm.get(m).toInt + e.get().toInt ) = 1.0
            m += 1
          }
        }
        (keys, output)
      }
    }
    res
  }

  /**
    * Inverse transform the input data - ie transform a one-hot encoded DrmLike[K] into a columnar matrix of the
    * original value. I.e. an Inverse of the Transform function.
    *
    * @param input one-hot DrmLike[K]
    * @return original DrmLike[K]
    */
  override def invTransform[K](input: DrmLike[K]): DrmLike[K] = {
    implicit val ctx = input.context

    val bcastK = drmBroadcast(dvec(cardinality))
    val bcastFactorMap = drmBroadcast(factorMap)

    implicit val ktag =  input.keyClassTag

    val res = input.mapBlock(cardinality) {
      case (keys, block: Matrix) => {
        val k: Int = bcastK.value.get(0).toInt
        val output = new DenseMatrix(block.nrow, bcastK.value.length)
        // This is how we take a vector of mapping to a map
        val fm = bcastFactorMap.all.toSeq.map(e => e.get -> e.index).toMap

        import MahoutCollections._
        val indexArray = Array(1.0) ++ bcastFactorMap.value.toArray.map(i => i.toInt)
        for (n <- 0 until output.nrow){
          val v = new DenseVector(bcastFactorMap.value.length)
          var m = 0
          for (e <- block(n, ::).asInstanceOf[RandomAccessSparseVector].iterateNonZero() ){
            v.setQuick(m, e.index - m)
            m += 1
          }
          output(n, ::) = v
        }
        (keys, output)
      }
    }
    res
  }

}
