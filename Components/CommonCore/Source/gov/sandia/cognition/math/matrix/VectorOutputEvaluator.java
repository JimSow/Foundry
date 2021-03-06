/*
 * File:                VectorOutputEvaluator.java
 * Authors:             Justin Basilico
 * Company:             Sandia National Laboratories
 * Project:             Cognitive Foundry
 *
 * Copyright March 12, 2009, Sandia Corporation.
 * Under the terms of Contract DE-AC04-94AL85000, there is a non-exclusive
 * license for use of this work by or on behalf of the U.S. Government. Export
 * of this program may require a license from the United States Government.
 * See CopyrightHistory.txt for complete details.
 *
 */

package gov.sandia.cognition.math.matrix;

import gov.sandia.cognition.evaluator.Evaluator;

/**
 * An interface for an evaluator that produces a vector of a fixed
 * dimensionality.
 * @param <InputType> Input type
 * @param <OutputType> Type of the output Vectorizable
 *
 * @author  Justin Basilico
 * @since   3.0
 */
public interface VectorOutputEvaluator<InputType, OutputType extends Vectorizable>
    extends Evaluator<InputType, OutputType>
{

    /**
     * Gets the expected dimensionality of the output vector of the evaluator,
     * if it is known. If it is not known, -1 is returned.
     *
     * @return
     *      The expected dimensionality of the output vector of the evaluator,
     *      or -1 if it is not known.
     */
    public int getOutputDimensionality();

}
