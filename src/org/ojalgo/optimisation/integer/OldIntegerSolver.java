/*
 * Copyright 1997-2017 Optimatika
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.ojalgo.optimisation.integer;

import static org.ojalgo.constant.PrimitiveMath.*;
import static org.ojalgo.function.PrimitiveFunction.*;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

import org.ojalgo.matrix.store.MatrixStore;
import org.ojalgo.matrix.store.PrimitiveDenseStore;
import org.ojalgo.netio.BasicLogger;
import org.ojalgo.netio.CharacterRing;
import org.ojalgo.netio.CharacterRing.PrinterBuffer;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.type.TypeUtils;

/**
 * IntegerSolver
 *
 * @author apete
 */
public final class OldIntegerSolver extends IntegerSolver {

    final class BranchAndBoundNodeTask extends RecursiveTask<Boolean> {

        private final NodeKey myKey;
        private final PrinterBuffer myPrinter = OldIntegerSolver.this.isDebug() ? new CharacterRing().asPrinter() : null;

        private BranchAndBoundNodeTask(final NodeKey key) {

            super();

            myKey = key;
        }

        BranchAndBoundNodeTask() {

            super();

            myKey = new NodeKey(OldIntegerSolver.this.getModel());
        }

        @Override
        public String toString() {
            return myKey.toString();
        }

        private boolean isNodeDebug() {
            return (myPrinter != null) && OldIntegerSolver.this.isDebug();
        }

        @Override
        protected Boolean compute() {

            final ExpressionsBasedModel nodeModel = OldIntegerSolver.this.getModel().relax(false);

            if (OldIntegerSolver.this.isIntegerSolutionFound()) {
                final double tmpBestValue = OldIntegerSolver.this.getBestResultSoFar().getValue();
                final double tmpGap = ABS.invoke(tmpBestValue * OldIntegerSolver.this.options.mip_gap);
                if (nodeModel.isMinimisation()) {
                    nodeModel.limitObjective(null, TypeUtils.toBigDecimal(tmpBestValue - tmpGap, OldIntegerSolver.this.options.problem));
                } else {
                    nodeModel.limitObjective(TypeUtils.toBigDecimal(tmpBestValue + tmpGap, OldIntegerSolver.this.options.problem), null);
                }
            }

            return this.compute(nodeModel);
        }

        protected Boolean compute(final ExpressionsBasedModel nodeModel) {

            if (this.isNodeDebug()) {
                myPrinter.println();
                myPrinter.println("Branch&Bound Node");
                myPrinter.println(myKey.toString());
                myPrinter.println(OldIntegerSolver.this.toString());
            }

            if (!OldIntegerSolver.this.isIterationAllowed() || !OldIntegerSolver.this.isIterationNecessary()) {
                if (this.isNodeDebug()) {
                    myPrinter.println("Reached iterations or time limit - stop!");
                    this.flush(OldIntegerSolver.this.getModel().options.logger_appender);
                }
                return false;
            }

            if (OldIntegerSolver.this.isExplored(this)) {
                if (this.isNodeDebug()) {
                    myPrinter.println("Node previously explored!");
                    this.flush(OldIntegerSolver.this.getModel().options.logger_appender);
                }
                return true;
            } else {
                OldIntegerSolver.this.markAsExplored(this);
            }

            if (!OldIntegerSolver.this.isGoodEnoughToContinueBranching(myKey.objective)) {
                if (this.isNodeDebug()) {
                    myPrinter.println("No longer a relevant node!");
                    this.flush(OldIntegerSolver.this.getModel().options.logger_appender);
                }
                return true;
            }

            myKey.bound(nodeModel, OldIntegerSolver.this.getIntegerIndices());

            final Result tmpBestResultSoFar = OldIntegerSolver.this.getBestResultSoFar();
            final Optimisation.Result tmpNodeResult = nodeModel.solve(tmpBestResultSoFar);

            if (this.isNodeDebug()) {
                myPrinter.println("Node Result: {}", tmpNodeResult);
            }

            OldIntegerSolver.this.incrementIterationsCount();

            if (tmpNodeResult.getState().isOptimal()) {
                if (this.isNodeDebug()) {
                    myPrinter.println("Node solved to optimality!");
                }

                if (OldIntegerSolver.this.options.validate && !nodeModel.validate(tmpNodeResult)) {
                    // This should not be possible. There is a bug somewhere.
                    myPrinter.println("Node solution marked as OPTIMAL, but is actually INVALID/INFEASIBLE/FAILED. Stop this branch!");
                    myPrinter.println("Lower bounds: {}", Arrays.toString(myKey.getLowerBounds()));
                    myPrinter.println("Upper bounds: {}", Arrays.toString(myKey.getUpperBounds()));

                    nodeModel.validate(tmpNodeResult, myPrinter);

                    this.flush(OldIntegerSolver.this.getModel().options.logger_appender);

                    return false;
                }

                final int tmpBranchIndex = OldIntegerSolver.this.identifyNonIntegerVariable(tmpNodeResult, myKey);
                final double tmpSolutionValue = OldIntegerSolver.this.evaluateFunction(tmpNodeResult);

                if (tmpBranchIndex == -1) {
                    if (this.isNodeDebug()) {
                        myPrinter.println("Integer solution! Store it among the others, and stop this branch!");
                    }

                    final Optimisation.Result tmpIntegerSolutionResult = new Optimisation.Result(Optimisation.State.FEASIBLE, tmpSolutionValue, tmpNodeResult);

                    OldIntegerSolver.this.markInteger(myKey, tmpIntegerSolutionResult);

                    if (this.isNodeDebug()) {
                        myPrinter.println(OldIntegerSolver.this.getBestResultSoFar().toString());
                        BasicLogger.debug();
                        BasicLogger.debug(OldIntegerSolver.this.toString());
                        // BasicLogger.debug(DaemonPoolExecutor.INSTANCE.toString());
                        this.flush(OldIntegerSolver.this.getModel().options.logger_appender);
                    }

                    nodeModel.dispose();
                    return true;

                } else {
                    if (this.isNodeDebug()) {
                        myPrinter.println("Not an Integer Solution: " + tmpSolutionValue);
                    }

                    final double tmpVariableValue = tmpNodeResult.doubleValue(OldIntegerSolver.this.getGlobalIndex(tmpBranchIndex));

                    if (OldIntegerSolver.this.isGoodEnoughToContinueBranching(tmpSolutionValue)) {

                        if (this.isNodeDebug()) {
                            myPrinter.println("Still hope, branching on {} @ {} >>> {}", tmpBranchIndex, tmpVariableValue,
                                    nodeModel.getVariable(OldIntegerSolver.this.getGlobalIndex(tmpBranchIndex)));
                            this.flush(OldIntegerSolver.this.getModel().options.logger_appender);
                        }

                        final BranchAndBoundNodeTask lowerBranch = this.createLowerBranch(tmpBranchIndex, tmpVariableValue, tmpSolutionValue);
                        final BranchAndBoundNodeTask upperBranch = this.createUpperBranch(tmpBranchIndex, tmpVariableValue, tmpSolutionValue);

                        final BranchAndBoundNodeTask nextTask;
                        final BranchAndBoundNodeTask forkedTask;

                        if ((tmpVariableValue - Math.floor(tmpVariableValue)) > HALF) {
                            nextTask = upperBranch;
                            forkedTask = lowerBranch;
                        } else {
                            nextTask = lowerBranch;
                            forkedTask = upperBranch;
                        }

                        forkedTask.fork();

                        return nextTask.compute(nodeModel) && forkedTask.join();

                    } else {
                        if (this.isNodeDebug()) {
                            myPrinter.println("Can't find better integer solutions - stop this branch!");
                            this.flush(OldIntegerSolver.this.getModel().options.logger_appender);
                        }

                        nodeModel.dispose();
                        return true;
                    }
                }

            } else {
                if (this.isNodeDebug()) {
                    myPrinter.println("Failed to solve node problem - stop this branch!");
                    this.flush(OldIntegerSolver.this.getModel().options.logger_appender);
                }

                nodeModel.dispose();
                return true;
            }

        }

        BranchAndBoundNodeTask createLowerBranch(final int branchIndex, final double nonIntegerValue, final double parentObjectiveValue) {

            final NodeKey tmpKey = myKey.createLowerBranch(branchIndex, nonIntegerValue, parentObjectiveValue);

            return new BranchAndBoundNodeTask(tmpKey);
        }

        BranchAndBoundNodeTask createUpperBranch(final int branchIndex, final double nonIntegerValue, final double parentObjectiveValue) {

            final NodeKey tmpKey = myKey.createUpperBranch(branchIndex, nonIntegerValue, parentObjectiveValue);

            return new BranchAndBoundNodeTask(tmpKey);
        }

        private void flush(final BasicLogger.Printer receiver) {
            if ((myPrinter != null) && (receiver != null)) {
                myPrinter.flush(receiver);
            }
        }

        NodeKey getKey() {
            return myKey;
        }

    }

    // private final Set<NodeKey> myExploredNodes = Collections.synchronizedSet(new HashSet<NodeKey>());

    OldIntegerSolver(final ExpressionsBasedModel model, final Options solverOptions) {

        super(model, solverOptions);

        //options.debug = System.out;
    }

    public Result solve(final Result kickStarter) {

        // Must verify that it actually is an integer solution
        // The kickStarter may be user-supplied
        if ((kickStarter != null) && kickStarter.getState().isFeasible() && this.getModel().validate(kickStarter)) {
            this.markInteger(null, kickStarter);
        }

        this.resetIterationsCount();

        final BranchAndBoundNodeTask rootNodeTask = new BranchAndBoundNodeTask();

        final boolean normalExit = ForkJoinPool.commonPool().invoke(rootNodeTask);

        final Optimisation.Result bestSolutionFound = this.getBestResultSoFar();

        if (bestSolutionFound.getState().isFeasible()) {
            if (normalExit) {
                return new Optimisation.Result(State.OPTIMAL, bestSolutionFound);
            } else {
                return new Optimisation.Result(State.FEASIBLE, bestSolutionFound);
            }
        } else {
            if (normalExit) {
                return new Optimisation.Result(State.INFEASIBLE, bestSolutionFound);
            } else {
                return new Optimisation.Result(State.FAILED, bestSolutionFound);
            }
        }
    }

    @Override
    public String toString() {
        return TypeUtils.format("Solutions={} Nodes/Iterations={} {}", this.countIntegerSolutions(), this.countExploredNodes(), this.getBestResultSoFar());
    }

    @Override
    protected MatrixStore<Double> extractSolution() {
        return PrimitiveDenseStore.FACTORY.columns(this.getBestResultSoFar());
    }

    @Override
    protected boolean initialise(final Result kickStarter) {
        return true;
    }

    @Override
    protected boolean needsAnotherIteration() {
        return !this.getState().isOptimal();
    }

    @Override
    protected boolean validate() {

        boolean retVal = true;
        this.setState(State.VALID);

        try {

            if (!(retVal = this.getModel().validate())) {
                retVal = false;
                this.setState(State.INVALID);
            }

        } catch (final Exception ex) {

            retVal = false;
            this.setState(State.FAILED);
        }

        return retVal;
    }

    int countExploredNodes() {
        // return myExploredNodes.size();
        return 0;
    }

    boolean isExplored(final BranchAndBoundNodeTask aNodeTask) {
        // return myExploredNodes.contains(aNodeTask.getKey());
        return false;
    }

    void markAsExplored(final BranchAndBoundNodeTask aNodeTask) {

        // myExploredNodes.add(aNodeTask.getKey());
    }

}
