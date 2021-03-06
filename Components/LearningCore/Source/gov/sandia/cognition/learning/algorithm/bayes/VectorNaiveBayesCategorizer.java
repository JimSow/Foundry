/*
 * File:                VectorNaiveBayesCategorizer.java
 * Authors:             Justin Basilico
 * Company:             Sandia National Laboratories
 * Project:             Cognitive Foundry Learning Core
 * 
 * Copyright November 24, 2010, Sandia Corporation.
 * Under the terms of Contract DE-AC04-94AL85000, there is a non-exclusive 
 * license for use of this work by or on behalf of the U.S. Government. Export 
 * of this program may require a license from the United States Government. 
 */

package gov.sandia.cognition.learning.algorithm.bayes;

import gov.sandia.cognition.collection.CollectionUtil;
import gov.sandia.cognition.learning.algorithm.AbstractBatchAndIncrementalLearner;
import gov.sandia.cognition.learning.algorithm.IncrementalLearner;
import gov.sandia.cognition.learning.algorithm.SupervisedBatchLearner;
import gov.sandia.cognition.learning.data.DatasetUtil;
import gov.sandia.cognition.learning.data.InputOutputPair;
import gov.sandia.cognition.learning.data.DefaultWeightedValueDiscriminant;
import gov.sandia.cognition.learning.function.categorization.Categorizer;
import gov.sandia.cognition.learning.function.categorization.DiscriminantCategorizer;
import gov.sandia.cognition.math.LogMath;
import gov.sandia.cognition.math.RingAccumulator;
import gov.sandia.cognition.math.matrix.Vector;
import gov.sandia.cognition.math.matrix.VectorInputEvaluator;
import gov.sandia.cognition.math.matrix.Vectorizable;
import gov.sandia.cognition.statistics.DataDistribution;
import gov.sandia.cognition.statistics.DistributionEstimator;
import gov.sandia.cognition.statistics.UnivariateProbabilityDensityFunction;
import gov.sandia.cognition.statistics.distribution.DefaultDataDistribution;
import gov.sandia.cognition.statistics.distribution.UnivariateGaussian;
import gov.sandia.cognition.util.AbstractCloneableSerializable;
import gov.sandia.cognition.util.ObjectUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A naive Bayesian categorizer that takes an input vector and applies an
 * independent scalar probability density function to each one.
 *
 * @param   <CategoryType>
 *      The output category type for the categorizer. Must implement equals and
 *      hash code.
 * @param   <DistributionType>
 *      The type of the distributions used to compute the conditionals for each
 *      dimension.
 * @author  Justin Basilico
 * @since   3.1
 */
public class VectorNaiveBayesCategorizer<CategoryType, DistributionType extends UnivariateProbabilityDensityFunction>
    extends AbstractCloneableSerializable
    implements Categorizer<Vectorizable, CategoryType>,
        VectorInputEvaluator<Vectorizable, CategoryType>,
        DiscriminantCategorizer<Vectorizable, CategoryType, Double>
{

    /** The prior distribution for the categorizer. */
    protected DataDistribution<CategoryType> priors;

    /** The mapping of category to the conditional distribution for the category
     *  with one probability density function for each dimension. */
    protected Map<CategoryType, List<DistributionType>> conditionals;

    /**
     * Creates a new {@code VectorNaiveBayesCategorizer} with an empty prior
     * and conditionals.
     */
    public VectorNaiveBayesCategorizer()
    {
        this(new DefaultDataDistribution<CategoryType>(),
            new LinkedHashMap<CategoryType, List<DistributionType>>());
    }

    /**
     * Creates a new {@code VectorNaiveBayesCategorizer} with the given prior
     * and conditionals.
     *
     * @param   priors
     *      The prior distribution.
     * @param   conditionals
     *      The conditional distribution.
     */
    public VectorNaiveBayesCategorizer(
        final DataDistribution<CategoryType> priors,
        final Map<CategoryType, List<DistributionType>> conditionals)
    {
        super();

        this.setPriors(priors);
        this.setConditionals(conditionals);
    }

    @Override
    public VectorNaiveBayesCategorizer<CategoryType, DistributionType> clone()
    {
        @SuppressWarnings("unchecked")
        final VectorNaiveBayesCategorizer<CategoryType, DistributionType> clone =
            (VectorNaiveBayesCategorizer<CategoryType, DistributionType>) super.clone();

        clone.priors = ObjectUtil.cloneSafe(this.priors);
        clone.conditionals =
            new LinkedHashMap<CategoryType, List<DistributionType>>(
            this.conditionals.size());
        for (CategoryType category : this.conditionals.keySet())
        {
            clone.conditionals.put(category,
                ObjectUtil.cloneSmartElementsAsArrayList(
                this.conditionals.get(category)));
        }

        return clone;
    }

    @Override
    public CategoryType evaluate(
        final Vectorizable input)
    {
        final Vector vector = input.convertToVector();

        // We want to find the category with the maximum posterior distribution.
        // This means we only have to compute the numerator of the class
        // probability formula, since the denominator is the same for every
        // class.
        double maxLogPosterior = Double.NEGATIVE_INFINITY;
        CategoryType maxCategory = null;
        for (CategoryType category : this.getCategories())
        {
            // Compute the posterior probability for the category.
            final double logPosterior = this.computeLogPosterior(
                vector, category);

            // See if the new posterior is the best found so far.
            if (maxCategory == null || logPosterior > maxLogPosterior)
            {
                maxLogPosterior = logPosterior;
                maxCategory = category;
            }
        }

        return maxCategory;
    }

    @Override
    public DefaultWeightedValueDiscriminant<CategoryType> evaluateWithDiscriminant(
        final Vectorizable input)
    {
        final Vector vector = input.convertToVector();

        // We want to find the category with the maximum posterior distribution.
        // We also compute the denominator in order to have a valid descriminant
        // value, which means adding together all of the posteriors.
        double maxLogPosterior = Double.NEGATIVE_INFINITY;
        double logDenominator = Double.NEGATIVE_INFINITY;
        CategoryType maxCategory = null;
        for (CategoryType category : this.getCategories())
        {
            // Compute the posterior probability for the category.
            final double logPosterior = this.computeLogPosterior(
                vector, category);

            // See if the new posterior is the best found so far.
            if (maxCategory == null || logPosterior > maxLogPosterior)
            {
                maxLogPosterior = logPosterior;
                maxCategory = category;
            }

            logDenominator = LogMath.add(logDenominator, logPosterior);
        }

        // The discriminant is the log of the maximum likelihood estimate,
        // which is the probability the input belongs to the most likely class.
        // This would be P(y) * P(x|y) / P(x), but since we are in log space,
        // the division is just substraction.
        final double logMaximumLikelihood = maxLogPosterior - logDenominator;
        return DefaultWeightedValueDiscriminant.create(
            maxCategory, logMaximumLikelihood);
    }

    /**
     * Computes the posterior probability that the input belongs to the
     * given category.
     *
     * @param   input
     *      The input vector.
     * @param   category
     *      The category to compute the posterior for.
     * @return
     *      The posterior probability that the input is part of the given
     *      category. Between 0.0 and 1.0.
     */
    public double computePosterior(
        final Vector input,
        final CategoryType category)
    {
        return Math.exp(this.computeLogPosterior(input, category));
    }

    /**
     * Computes the log-posterior probability that the input belongs to the
     * given category.
     *
     * @param   input
     *      The input vector.
     * @param   category
     *      The category to compute the posterior for.
     * @return
     *      The log-posterior probability.
     */
    public double computeLogPosterior(
        final Vector input,
        final CategoryType category)
    {
        // Get the prior for the class.
        final double priorProbability = this.priors.getFraction(category);

        // Now compute the posterior by looking at the probability density
        // function for each dimension. We loop until
        double logPosterior = Math.log(priorProbability);
        final List<DistributionType> probabilityFunctions =
            this.conditionals.get(category);
        final int size = probabilityFunctions.size();
        for (int i = 0; i < size; i++)
        {
            // Get the value for the element.
            final double value = input.getElement(i);
            final double x = probabilityFunctions.get(i).logEvaluate(value);

            // Update the posterior.
            logPosterior += x;
        }

        return logPosterior;
    }

    @Override
    public Set<CategoryType> getCategories()
    {
        return this.conditionals.keySet();
    }

    @Override
    public int getInputDimensionality()
    {
        // The dimensionality is the size of the first list (which should be
        // the same as the size of all the others).
        final List<DistributionType> first =
            CollectionUtil.getFirst(this.conditionals.values());

        return first == null ? 0 : first.size();
    }

    /**
     * Gets the prior distribution over the categories.
     *
     * @return
     *      The prior distribution over the categories.
     */
    public DataDistribution<CategoryType> getPriors()
    {
        return this.priors;
    }

    /**
     * Sets the prior distribution over the categories.
     *
     * @param   priors
     *      The prior distribution over the categories.
     */
    public void setPriors(
        final DataDistribution<CategoryType> priors)
    {
        this.priors = priors;
    }

    /**
     * Gets the conditional distributions, which is a mapping of category to
     * the list of probability density functions, one for each dimension of the
     * vector.
     *
     * @return
     *      The conditional distributions for each category.
     */
    public Map<CategoryType, List<DistributionType>> getConditionals()
    {
        return this.conditionals;
    }

    /**
     * Sets the conditional distributions, which is a mapping of category to
     * the list of probability density functions, one for each dimension of the
     * vector.
     *
     * @param   conditionals
     *      The conditional distributions for each category.
     */
    public void setConditionals(
        final Map<CategoryType, List<DistributionType>> conditionals)
    {
        this.conditionals = conditionals;
    }

    /**
     * A supervised batch distributionLearner for a vector Naive Bayes categorizer.
     *
     * @param   <CategoryType>
     *      The output category type for the categorizer. Must implement equals and
     *      hash code.
     * @param   <DistributionType>
     *      The type of distribution that the distributionLearner produces.
     */
    public static class Learner<CategoryType, DistributionType extends UnivariateProbabilityDensityFunction>
        extends AbstractCloneableSerializable
        implements SupervisedBatchLearner<Vectorizable, CategoryType, VectorNaiveBayesCategorizer<CategoryType, DistributionType>>
    {

        /** The distributionLearner for the distribution of each dimension of each category. */
        protected DistributionEstimator<? super Double, ? extends DistributionType> distributionEstimator;

        /**
         * Creates a new {@code BatchLearner} with a null estimator.
         */
        public Learner()
        {
            this(null);
        }

        /**
         * Creates a new {@code BatchLearner} with the given distribution
         * estimator.
         *
         * @param   distributionEstimator
         *      The estimator for the distribution of each dimension of each
         *      category.
         */
        public Learner(
            final DistributionEstimator<? super Double, ? extends DistributionType> distributionEstimator)
        {
            super();

            this.setDistributionEstimator(distributionEstimator);
        }

        @Override
        public VectorNaiveBayesCategorizer<CategoryType, DistributionType> learn(
            final Collection<? extends InputOutputPair<? extends Vectorizable, CategoryType>> data)
        {
            // Split the data by category.
            final int dimensionality = DatasetUtil.getInputDimensionality(data);
            final Map<CategoryType, List<Vectorizable>> examplesPerCategory =
                DatasetUtil.splitOnOutput(data);

            // Create the categorizer to store the result.
            final VectorNaiveBayesCategorizer<CategoryType, DistributionType> result =
                new VectorNaiveBayesCategorizer<CategoryType, DistributionType>();

            final ArrayList<Double> values = new ArrayList<Double>(data.size());

            // Go through the categories.
            for (CategoryType category : examplesPerCategory.keySet())
            {
                // Get the examples for that category.
                final List<Vectorizable> examples =
                    examplesPerCategory.get(category);
                final int count = examples.size();

                // Go through all the dimensions and create the conditional
                // distribution for it.
                final List<DistributionType> conditionals =
                    new ArrayList<DistributionType>(
                        dimensionality);
                for (int i = 0; i < dimensionality; i++)
                {
                    // Add the values for the given dimension to the array.
                    for (Vectorizable input : examples)
                    {
                        values.add(input.convertToVector().getElement(i));
                    }

                    // Create the univariate gaussian PDF.
                    conditionals.add(this.distributionEstimator.learn(values));
                    
                    // Clear the reusable array of values.
                    values.clear();
                }

                // Add the category to the priors and its conditional.
                result.priors.increment(category, count);
                result.conditionals.put(category, conditionals);
            }

            return result;
        }

        /**
         * Gets the estimation method for the distribution of each dimension of
         * each category.
         *
         * @return
         *      The estimator for the distribution of each dimension of each
         *      category.
         */
        public DistributionEstimator<? super Double, ? extends DistributionType> getDistributionEstimator()
        {
            return this.distributionEstimator;
        }

        /**
         * Sets the estimation method for the distribution of each dimension of
         * each category.
         *
         * @param   distributionEstimator
         *      The estimator for the distribution of each dimension of each
         *      category.
         */
        public void setDistributionEstimator(
            final DistributionEstimator<? super Double, ? extends DistributionType> distributionEstimator)
        {
            this.distributionEstimator = distributionEstimator;
        }

    }

    /**
     * A supervised batch distributionLearner for a vector Naive Bayes categorizer that fits
     * a Gaussian.
     *
     * @param   <CategoryType>
     *      The output category type for the categorizer. Must implement equals and
     *      hash code.
     */
    public static class BatchGaussianLearner<CategoryType>
        extends AbstractCloneableSerializable
        implements SupervisedBatchLearner<Vectorizable, CategoryType, VectorNaiveBayesCategorizer<CategoryType, UnivariateGaussian.PDF>>
    {

        /**
         * Creates a new {@code BatchGaussianLearner}.
         */
        public BatchGaussianLearner()
        {
            super();
        }

        @Override
        public VectorNaiveBayesCategorizer<CategoryType, UnivariateGaussian.PDF> learn(
            final Collection<? extends InputOutputPair<? extends Vectorizable, CategoryType>> data)
        {
            // Split the data by category.
            final int dimensionality = DatasetUtil.getInputDimensionality(data);
            final Map<CategoryType, List<Vectorizable>> examplesPerCategory =
                DatasetUtil.splitOnOutput(data);

            // Create the categorizer to store the result.
            final VectorNaiveBayesCategorizer<CategoryType, UnivariateGaussian.PDF> result =
                new VectorNaiveBayesCategorizer<CategoryType, UnivariateGaussian.PDF>();

            // Go through the categories.
            for (CategoryType category : examplesPerCategory.keySet())
            {
                // Get the examples for that category.
                final List<Vectorizable> examples =
                    examplesPerCategory.get(category);

                // Try to compute the mean and variance for each dimension in
                // one pass by using the sum of values and the sum of squared
                // values.
                final RingAccumulator<Vector> sumsAccumulator =
                    new RingAccumulator<Vector>();
                final RingAccumulator<Vector> sumsOfSquaresAccumulator =
                    new RingAccumulator<Vector>();
                for (Vectorizable input : examples)
                {
                    final Vector vector = input.convertToVector();
                    sumsAccumulator.accumulate(vector);
                    sumsOfSquaresAccumulator.accumulate(vector.dotTimes(vector));
                }

                // Transform the accumuators into vectors.
                final Vector sums = sumsAccumulator.getSum();
                final Vector sumsOfSquares = sumsOfSquaresAccumulator.getSum();

                // Figure out the number of instances and the denominator for
                // the variance. We check for values greater than 1 to avoid a
                // divide-by-zero.
                final int count = examples.size();
                final long varianceDenominator =
                    count > 1 ? (count - 1) : 1;
                final List<UnivariateGaussian.PDF> conditionals =
                    new ArrayList<UnivariateGaussian.PDF>(dimensionality);
                
                for (int i = 0; i < dimensionality; i++)
                {
                    // Figure out the mean and variance.
                    final double sum = sums.getElement(i);
                    final double sumOfSquares = sumsOfSquares.getElement(i);
                    final double mean = sum / count;
                    final double variance = (sumOfSquares - sum * mean)
                        / varianceDenominator;

                    // Create the univariate gaussian PDF.
                    conditionals.add(
                        new UnivariateGaussian.PDF(mean, variance));
                }

                // Add the category to the priors and its conditional.
                result.priors.increment(category, count);
                result.conditionals.put(category, conditionals);
            }

            return result;
        }

    }

    /**
     * An online (incremental) distributionLearner for the Naive Bayes 
     * categorizer that uses an incremental distribution learner for the
     * distribution representing each dimension for each category.
     *
     * @param   <CategoryType>
     *      The output category type for the categorizer. Must implement equals and
     *      hash code.
     * @param   <DistributionType>
     *      The type of the distributions used to compute the conditionals for each
     *      dimension.
     * @author  Justin Basilico
     * @since   3.3.0
     */
    public static class OnlineLearner<CategoryType, DistributionType extends UnivariateProbabilityDensityFunction>
        extends AbstractBatchAndIncrementalLearner<InputOutputPair<? extends Vectorizable, CategoryType>, VectorNaiveBayesCategorizer<CategoryType, DistributionType>>
    {
        /** The incremental learner for the distribution used to represent each
         *  dimension. By the generic, it must learn a univariate probability
         *  density function. */
        protected IncrementalLearner<? super Double, DistributionType> distributionLearner;

        /**
         * Creates a new learner with a null distribution learner.
         */
        public OnlineLearner()
        {
            this(null);
        }

        /**
         * Creates a new learner with a given distribution learner.
         *
         * @param   distributionLearner
         *      The learner for the distribution representing each dimension.
         */
        public OnlineLearner(
            final IncrementalLearner<? super Double, DistributionType> distributionLearner)
        {
            super();

            this.setDistributionLearner(distributionLearner);
        }

        @Override
        public VectorNaiveBayesCategorizer<CategoryType, DistributionType> createInitialLearnedObject()
        {
            return new VectorNaiveBayesCategorizer<CategoryType, DistributionType>();
        }

        @Override
        public void update(
            final VectorNaiveBayesCategorizer<CategoryType, DistributionType> target,
            final InputOutputPair<? extends Vectorizable, CategoryType> data)
        {
            // Get the input vector and the output category.
            final Vector input = data.getInput().convertToVector();
            final CategoryType category = data.getOutput();

            // Increment the priors for the category.
            target.getPriors().increment(category);

            List<DistributionType> conditionals =
                target.getConditionals().get(category);

            final int dimensionality = input.getDimensionality();
            if (conditionals == null)
            {
                // Have not seen this category yet. Initialize it.
                conditionals = new ArrayList<DistributionType>(dimensionality);

                for (int i = 0; i < dimensionality; i++)
                {
                    conditionals.add(
                        this.distributionLearner.createInitialLearnedObject());
                }

                target.getConditionals().put(category, conditionals);
            }

            // Update all the conditionals for the category.
            for (int i = 0; i < dimensionality; i++)
            {
                final DistributionType conditional = conditionals.get(i);

                this.distributionLearner.update(
                    conditional, input.getElement(i));
            }
        }

        /**
         * Gets the learner used for the distribution representing each
         * dimension.
         *
         * @return
         *      The distribution learner.
         */
        public IncrementalLearner<? super Double, DistributionType> getDistributionLearner()
        {
            return this.distributionLearner;
        }

        /**
         * Sets the learner used for the distribution representing each
         * dimension.
         *
         * @param   distributionLearner
         *      The distribution learner.
         */
        public void setDistributionLearner(
            final IncrementalLearner<? super Double, DistributionType> distributionLearner)
        {
            this.distributionLearner = distributionLearner;
        }


    }


}
