package tardis.implementation.data;

import static tardis.implementation.common.Util.filterOnPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
//import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jbse.mem.Clause;
import tardis.Options;
import tardis.framework.InputBuffer;
import tardis.framework.OutputBuffer;
import tardis.implementation.data.ClassifierKNN.ClassificationResult;
import tardis.implementation.jbse.JBSEResult;

/**
 * An {@link InputBuffer} and {@link OutputBuffer} for {@link JBSEResult}s that
 * prioritizes {@link JBSEResult}s based on several heuristics.
 * 
 * @author Pietro Braione
 *
 * @param <E> the type of the items stored in the buffer.
 */
public final class JBSEResultInputOutputBuffer implements InputBuffer<JBSEResult>, OutputBuffer<JBSEResult> {
	/** The logger. */
    private static final Logger LOGGER = LogManager.getFormatterLogger(JBSEResultInputOutputBuffer.class);
    
	/** The maximum value for the index of improvability. */
    private static final int INDEX_IMPROVABILITY_MAX = 10;
    
    /** The minimum value for the index of novelty. */
    private static final int INDEX_NOVELTY_MIN = 0;
    
    /** The maximum value for the index of novelty. */
    private static final int INDEX_NOVELTY_MAX = 10;
    
    /** 
     * The queue numbers, from the best to the worst.
     * This is the case where zero indices are active. 
     */
    private static final int[] QUEUE_RANKING_0_INDICES = {0};
    
    /** 
     * The queue numbers, from the best to the worst.
     * This is the case where the only active index is 
     * the improvability one. 
     */
    private static final int[] QUEUE_RANKING_1_INDEX_IMPROVABILITY = {10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
    
    /** 
     * The queue numbers, from the best to the worst.
     * This is the case where the only active index is 
     * the novelty one. 
     */
    private static final int[] QUEUE_RANKING_1_INDEX_NOVELTY = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    
    /** 
     * The queue numbers, from the best to the worst.
     * This is the case where the only active index is 
     * the infeasibility one. 
     */
    private static final int[] QUEUE_RANKING_1_INDEX_INFEASIBILITY = {3, 2, 1, 0};
    
    /** 
     * The queue numbers, from the best to the worst.
     * This is the case where two indices are active. 
     */
    private static final int[] QUEUE_RANKING_2_INDICES = {2, 1, 0};
    
    /** 
     * The queue numbers, from the best to the worst.
     * This is the case where three indices are active. 
     */
    private static final int[] QUEUE_RANKING_3_INDICES = {3, 2, 1, 0};
    
    /**
     * The probabilities of choice for each queue.
     * This is the case where zero indices are active. 
     */
    private static final int[] QUEUE_PROBABILITIES_0_INDICES = {100};
    
    /**
     * The probabilities of choice for each queue.
     * This is the case where the only active index is 
     * the improvability one. 
     */
    private static final int[] QUEUE_PROBABILITIES_1_INDEX_IMPROVABILITY = {50, 12, 9, 7, 6, 5, 4, 3, 2, 1, 1};
    
    /**
     * The probabilities of choice for each queue.
     * This is the case where the only active index is 
     * the novelty one. 
     */
    private static final int[] QUEUE_PROBABILITIES_1_INDEX_NOVELTY = {50, 12, 9, 7, 6, 5, 4, 3, 2, 1, 1};
    
    /**
     * The probabilities of choice for each queue.
     * This is the case where the only active index is 
     * the infeasibility one. 
     */
    private static final int[] QUEUE_PROBABILITIES_1_INDEX_INFEASIBILITY = {50, 30, 15, 5};
    
    /**
     * The probabilities of choice for each queue.
     * This is the case where two indices are active. 
     */
    private static final int[] QUEUE_PROBABILITIES_2_INDICES = {60, 30, 10};
    
    /**
     * The probabilities of choice for each queue.
     * This is the case where three indices are active. 
     */
    private static final int[] QUEUE_PROBABILITIES_3_INDICES = {50, 30, 15, 5};
    
    /** The K value for the KNN classifier. */
    private static final int K = 1;
    
    /** The KNN classifier used to calculate the infeasibility index. */
    private final ClassifierKNN classifier = new ClassifierKNN(K);

    /** Buffers the next covered branches for the improvability index. */
    private final HashSet<String> coverageSetImprovability = new HashSet<>();
    
    /** Buffers the next covered branches for the novelty index. */
    private final HashSet<String> coverageSetNovelty = new HashSet<>();
    
    /** 
     * {@code true} iff this buffer shall use the improvability index to
     * rank {@link JBSEResult}s.
     */
    private final boolean useIndexImprovability;
    
    /** 
     * {@code true} iff this buffer shall use the novelty index to
     * rank {@link JBSEResult}s.
     */
    private final boolean useIndexNovelty;
    
    /** 
     * {@code true} iff this buffer shall use the infeasibility index to
     * rank {@link JBSEResult}s.
     */
    private final boolean useIndexInfeasibility;
    
    /** 
     * The pattern of the branches that shall be considered for the improvability
     * index calculation.
     */
    private final String patternBranchesImprovability;
    
    /** 
     * The pattern of the branches that shall be considered for the novelty
     * index calculation.
     */
    private final String patternBranchesNovelty;
    
    /** The order of the queues, from the most desirable to the least one. */
    private final int[] queueRanking;

    /** The probability of choosing a queue (ranges from 0 to 100). */
    private final int[] queueProbabilities;
    
    /** The minimum size of the training set necessary for resorting the queues. */
    private final int trainingSetMinimumThreshold;
    
    /** The {@link TreePath} used to store information about the path conditions. */
    private final TreePath treePath;

    /** The queues where the {@link JBSEResult}s are stored. */
    private final HashMap<Integer, LinkedBlockingQueue<JBSEResult>> queues = new HashMap<>();
    
    /** 
     * The number of training samples learned by the KNN classifier since
     * the last reclassification of the queues items
     */
    private int trainingSetSize = 0;
    
    
    
    /**
     * How many possible voting values there are, which go from floor(K/2)+1 to K,
     * so K - (floor(K/2)+1) + 1 = K - floor(K/2)-1+1 = K - floor(K/2)
     * 
     * e.g. if K = 10 then we can have {6,7,8,9,10} so 5
     * e.g. if K = 11 then we can have {6,7,8,9,10,11} so 6
     */
    private static final int NUMBER_OF_POSSIBLE_VOTING = K - K / 2;
    
    /**
     * How many queues are never used, so how many are skipped from the left,
     * needed to avoid having wrong probabilities when choosing unused queues
     * 
     * e.g. if we have K = 1 and 4 queues then offset = 2, so the first two queues aren't used
     */
    private final int offset;
    
    /**
     * Length of the sub-array which is actually used, which starts from queueRanking[offset] and ends like queueRanking
     */
    private final int newQueueRankingLength;
    
    //private static final ConcurrentHashMap<String, Boolean> classificationLabels = new ConcurrentHashMap<>(); //new, thread-safe
    
    public JBSEResultInputOutputBuffer(Options o, TreePath treePath) {
    	this.useIndexImprovability = o.getUseIndexImprovability();
    	this.useIndexNovelty = o.getUseIndexNovelty();
    	this.useIndexInfeasibility = o.getUseIndexInfeasibility();
    	this.patternBranchesImprovability = (o.getIndexImprovabilityBranchPattern() == null ? o.patternBranchesTarget() : o.getIndexImprovabilityBranchPattern());
    	this.patternBranchesNovelty = (o.getIndexNoveltyBranchPattern() == null ? o.patternBranchesTarget() : o.getIndexNoveltyBranchPattern());
    	this.queueRanking = queueRanking();
    	this.queueProbabilities = queueProbabilities();
    	this.trainingSetMinimumThreshold = o.getIndexInfeasibilityThreshold();
        this.treePath = treePath;
        for (int i = 0; i < queueRanking.length; ++i) {
            this.queues.put(i, new LinkedBlockingQueue<>());
        }
        this.offset = calculateOffset();
        this.newQueueRankingLength = queueRanking.length - offset;
    }
    
    private int calculateOffset() {
    	final int offset; //how many queues to skip from the left
        //to calculate it: it's the number of unused queues (feasible + infeasible)
        
        //# unused queues = # queues - # used queues, 
        //so consider number of possible queues - number of possible voting values
        	
        final int unusedFeasibleQueues;
        final int unusedInfeasibleQueues = (this.queueRanking.length / 2) - NUMBER_OF_POSSIBLE_VOTING;
        	
        if (this.queueRanking.length % 2 == 1) { //this is based on the calculation for the number of possible queues
        	unusedFeasibleQueues = ((this.queueRanking.length / 2) + 1) - NUMBER_OF_POSSIBLE_VOTING;
        } else {
        	unusedFeasibleQueues = unusedInfeasibleQueues;
        }
        	
        if (unusedFeasibleQueues + unusedInfeasibleQueues > 0) {
        	//the linear transformation (used in the updateIndexInfeasibility method) is not surjective
        	offset = unusedFeasibleQueues + unusedInfeasibleQueues;
        } else {
        	//the linear transformation (used in the updateIndexInfeasibility method) is not injective if NUMBER_OF_POSSIBLE_VOTING is too high, 
        	//so the cardinality of the domain is greater than the codomain's (number of possible queues),
        	//we don't care about this as long as it's surjective
        	offset = 0;
        }
        
        return offset;
    }

    @Override
    public synchronized boolean add(JBSEResult item) {
    	final String entryPoint = item.getTargetMethodSignature();
        final List<Clause> pathCondition = item.getPathConditionGenerated();
        if (this.useIndexImprovability) {
        	updateIndexImprovability(entryPoint, pathCondition);
        }
        if (this.useIndexNovelty) {
        	updateIndexNovelty(entryPoint, pathCondition);
        }
        if (this.useIndexInfeasibility) {
        	updateIndexInfeasibility(entryPoint, pathCondition, false);
        }
        final int queueNumber = calculateQueueNumber(entryPoint, pathCondition);
        /*if (queueRanking[queueNumber] < queueRanking.length - 1) {
			LOGGER.info("Priority path condition with last clause: " + pathCondition.get(pathCondition.size() - 1) + " -- priority=" + queueNumber + " (wrt min priority=" + queueRanking[queueRanking.length - 1] + ")");
        }*/
        final LinkedBlockingQueue<JBSEResult> queue = this.queues.get(queueNumber);
        return queue.add(item);
    }

    @Override
    public List<JBSEResult> pollN(int n, long timeoutDuration, TimeUnit timeoutTimeUnit) throws InterruptedException {
        //chooses the index considering the different probabilities
        final Random random = new Random();
        int randomValue = random.nextInt(100); //sum of PROBABILITY_VALUES
        int sum = 0;
        int j = 0;
        do {
            sum += this.queueProbabilities[j++];
        } while (sum < randomValue && j < this.queueRanking.length);
        
        //assert (0 < j && j <= INDEX_VALUES.length)
        
        /*synchronized (this) {
        	if (this.useIndexInfeasibility && (this.queues.get(1).size() != 0 || this.queues.get(0).size() != 0)) {
            	LOGGER.debug("There are %d items in the FEASIBLE queue and %d items in the INFEASIBLE queue", this.queues.get(1).size(), this.queues.get(0).size());
            }
        }*/

        final ArrayList<JBSEResult> retVal = new ArrayList<>();
        for (int k = 1; k <= n; ++k) {
        	JBSEResult item = null;
        	synchronized (this) {
        		//extracts the item, first chance
        		for (int i = j - 1; i < this.queueRanking.length; ++i) {
        			final LinkedBlockingQueue<JBSEResult> queue = this.queues.get(this.queueRanking[i]);
        			//selects the next queue if the extracted queue is empty
        			if (queue.isEmpty()) {
        				continue;
        			} else {
        				item = queue.poll(0, timeoutTimeUnit); //nothing to wait
        				if (item != null) {
        					LOGGER.debug("Got an item from queue %d", this.queueRanking[i]);
        				}
        				break;
        			}
        		}
        		//extracts the item, second chance
        		if (item == null) {
            		timeoutTimeUnit.sleep(timeoutDuration);
        			for (int i = j - 2; i >= 0; --i) {
        				final LinkedBlockingQueue<JBSEResult> queue = this.queues.get(this.queueRanking[i]);
        				//selects the next queue if the extracted queue is empty
        				if (queue.isEmpty()) {
        					continue;
        				} else {
        					item = queue.poll(0, timeoutTimeUnit); //nothing to wait
        					if (item != null) {
        						LOGGER.debug("Second chance, got an item from queue %d", this.queueRanking[i]);
        					}
        					break;
        				}
        			}
        		}
        	}
        	if (item == null) {
        		break;
        	} else {
        		retVal.add(item);
        	}
        }
        return retVal;
    }

    @Override
    public synchronized boolean isEmpty() {
        for (LinkedBlockingQueue<JBSEResult> queue : this.queues.values()) {
            if (!queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Caches the fact that a set of branches was covered.
     * Used to recalculate the improvability index.
     * 
     * @param newCoveredBranches a {@link Set}{@code <}{@link String}{@code >},
     *        the newly covered (i.e., not previously covered) branches.
     */
    public synchronized void learnCoverageForIndexImprovability(Set<String> newCoveredBranches) {
    	final Set<String> filtered = filterOnPattern(newCoveredBranches, this.patternBranchesImprovability);
        this.coverageSetImprovability.addAll(filtered);
    }
    
    /**
     * Caches the fact that a set of branches was covered.
     * Used to recalculate the novelty index.
     * 
     * @param coveredBranches a {@link Set}{@code <}{@link String}{@code >},
     *        the covered branches.
     */
    public synchronized void learnCoverageForIndexNovelty(Set<String> coveredBranches) {
    	final Set<String> filtered = filterOnPattern(coveredBranches, this.patternBranchesNovelty);
        this.coverageSetNovelty.addAll(filtered);
    }
    
    /**
     * Caches the fact that a path condition was successfully solved or not. 
     * Used to recalculate the infeasibility index.
     * 
     * @param entryPoint a {@link String}, the 
     *        identifier of a method's entry point where the
     *        path starts. 
     * @param path a {@link List}{@code <}{@link Clause}{@code >}. 
     *        The first is the closest to the root, the last is the leaf.
     * @param solved a {@code boolean}, {@code true} if the path
     *        condition was solved, {@code false} otherwise.
     */
    public synchronized void learnPathConditionForIndexInfeasibility(String entryPoint, List<Clause> path, boolean solved) {
    	final HashSet<TrainingItem> trainingSet = new HashSet<>();
        if (solved) {
            //all the prefixes are also solved
            for (int i = path.size(); i > 0; --i) {
                final BloomFilter bloomFilter = this.treePath.getBloomFilter(entryPoint, path.subList(0, i));
                trainingSet.add(new TrainingItem(bloomFilter, true));
            }
        } else {
            final BloomFilter bloomFilter = this.treePath.getBloomFilter(entryPoint, path);
            trainingSet.add(new TrainingItem(bloomFilter, false));
        }
        this.classifier.train(trainingSet);
        this.trainingSetSize += trainingSet.size();
    }

    /**
     * Recalculates the improvability index of all the {@link JBSEResult}s
     * stored in this buffer and reclassifies their priorities. 
     */
    public synchronized void updateIndexImprovabilityAndReclassify() {
        synchronized (this.treePath) {
            forAllQueuedItemsToUpdateImprovability((queueNumber, bufferedJBSEResult) -> {
            	final String entryPoint = bufferedJBSEResult.getTargetMethodSignature();
                final List<Clause> pathCondition = bufferedJBSEResult.getPathConditionGenerated();
                updateIndexImprovability(entryPoint, pathCondition);
                final int queueNumberNew = calculateQueueNumber(entryPoint, pathCondition);
                if (queueNumberNew != queueNumber) {
                    this.queues.get(queueNumber).remove(bufferedJBSEResult);
                    this.queues.get(queueNumberNew).add(bufferedJBSEResult);
                    LOGGER.info("Priority update for path condition with last clause: " + pathCondition.get(pathCondition.size() - 1) + " -- priority=" + queueNumber + " --> " + queueNumberNew + " (wrt min priority=" + queueRanking[queueRanking.length - 1] + ")");
                }
            });
            this.coverageSetImprovability.clear();
        }
    }
            
    /**
     * Recalculates the novelty index of all the {@link JBSEResult}s
     * stored in this buffer and reclassifies their priorities. 
     */
    public synchronized void updateIndexNoveltyAndReclassify() {
        synchronized (this.treePath) {
            forAllQueuedItemsToUpdateNovelty((queueNumber, bufferedJBSEResult) -> {
            	final String entryPoint = bufferedJBSEResult.getTargetMethodSignature();
                final List<Clause> pathCondition = bufferedJBSEResult.getPathConditionGenerated();
                updateIndexNovelty(entryPoint, pathCondition);
                final int queueNumberNew = calculateQueueNumber(entryPoint, pathCondition);
                if (queueNumberNew != queueNumber) {
                    this.queues.get(queueNumber).remove(bufferedJBSEResult);
                    this.queues.get(queueNumberNew).add(bufferedJBSEResult);
                }
            });
            this.coverageSetNovelty.clear();
        }
    }

    /**
     * Recalculates the infeasibility index of all the {@link JBSEResult}s
     * stored in this buffer and reclassifies their priorities. 
     */
    public synchronized void updateIndexInfeasibilityAndReclassify() {
        synchronized (this.treePath) {
            //reclassifies the queued items only if this.trainingSetSize is big enough
            if (this.trainingSetSize >= this.trainingSetMinimumThreshold) {
                forAllQueuedItems((queueNumber, bufferedJBSEResult) -> {
					//LOGGER.info("[reclassify] This should get printed at least once");
                	final String entryPoint = bufferedJBSEResult.getTargetMethodSignature();
                    final List<Clause> pathCondition = bufferedJBSEResult.getPathConditionGenerated();
                    updateIndexInfeasibility(entryPoint, pathCondition, true);
                    final int queueNumberNew = calculateQueueNumber(entryPoint, pathCondition);
                    if (queueNumberNew != queueNumber) {
                        this.queues.get(queueNumber).remove(bufferedJBSEResult);
                        this.queues.get(queueNumberNew).add(bufferedJBSEResult);
                        /*if (queueNumberNew < queueNumber) {
                        	LOGGER.info("[reclassify] New queue number (%d) is lower than the old one (%d), path condition: %s", 
                        			queueNumberNew, queueNumber, stringifyPostFrontierPathCondition(pathCondition));
                        }*/
                    }
                });
                this.trainingSetSize = 0;
            }
        }
    }
    
    /**
     * Gets the ranking of the queue numbers, from the best to the worst.
     * 
     * @return an {@code int[]} containing the queue numbers sorted from
     *         the best to the worst.
     */
    private int[] queueRanking() {
    	int count = 0;
    	final boolean[] useIndices = {this.useIndexImprovability, this.useIndexNovelty, this.useIndexInfeasibility};
    	for (boolean useIndex : useIndices) {
    		if (useIndex) {
    			++count;
    		}
    	}
    	switch (count) {
    	case 3:
    		return QUEUE_RANKING_3_INDICES;
    	case 2:
    		return QUEUE_RANKING_2_INDICES;
    	case 1:
    		if (this.useIndexImprovability) {
    			return QUEUE_RANKING_1_INDEX_IMPROVABILITY;
    		} else if (this.useIndexNovelty) {
    			return QUEUE_RANKING_1_INDEX_NOVELTY;
    		} else { //this.useIndexInfeasibility
    			return QUEUE_RANKING_1_INDEX_INFEASIBILITY;
    		}
    	case 0:
    		return QUEUE_RANKING_0_INDICES;
    	default:
    		throw new AssertionError("The number of active indices used to set up JBSEResultInputOutputBuffer is not between 0 and 3");
    	}
    }
    
    /**
     * Gets the probabilities of choice of the queues.
     * 
     * @return an {@code int[]} containing the probabilities of choice
     *         of each queue. Note that the probability at position {@code i}
     *         is the probability of the queue whose number is {@link #queueRanking()}{@code [i]}.
     */
    private int[] queueProbabilities() {
    	int count = 0;
    	final boolean[] useIndices = {this.useIndexImprovability, this.useIndexNovelty, this.useIndexInfeasibility};
    	for (boolean useIndex : useIndices) {
    		if (useIndex) {
    			++count;
    		}
    	}
    	switch (count) {
    	case 3:
    		return QUEUE_PROBABILITIES_3_INDICES;
    	case 2:
    		return QUEUE_PROBABILITIES_2_INDICES;
    	case 1:
    		if (this.useIndexImprovability) {
    			return QUEUE_PROBABILITIES_1_INDEX_IMPROVABILITY;
    		} else if (this.useIndexNovelty) {
    			return QUEUE_PROBABILITIES_1_INDEX_NOVELTY;
    		} else { //this.useIndexInfeasibility
    			return QUEUE_PROBABILITIES_1_INDEX_INFEASIBILITY;
    		}
    	case 0:
    		return QUEUE_PROBABILITIES_0_INDICES;
    	default:
    		throw new AssertionError("The number of active indices used to set up JBSEResultInputOutputBuffer is not between 0 and 3");
    	}
    }
    
    /**
     * Calculates the queue of a {@link JBSEResult} based on the path condition of its
     * final state.
     * 
     * @param entryPoint a {@link String}, the 
     *        identifier of a method's entry point where the
     *        path starts. 
     * @param path a {@link List}{@code <}{@link Clause}{@code >}. 
     *        The first is the closest to the root, the last is the leaf.
     * @return an {@code int} between {@code 0} and {@code 3}: the queue of the {@link JBSEResult}
     *         whose associated path condition is {@code path}. 
     */
    private int calculateQueueNumber(String entryPoint, List<Clause> path) {
        //gets the indices
    	/*final int indexImprovability = this.treePath.getIndexImprovability(entryPoint, path);
    	final int indexNovelty = this.treePath.getIndexNovelty(entryPoint, path);
    	final int indexInfeasibility = this.treePath.getIndexInfeasibility(entryPoint, path);*/

		if (this.useIndexImprovability && !this.useIndexNovelty && !this.useIndexInfeasibility) {
			return this.treePath.getIndexImprovability(entryPoint, path);
		} else if (!this.useIndexImprovability && this.useIndexNovelty && !this.useIndexInfeasibility) {
			return this.treePath.getIndexNovelty(entryPoint, path);
		} else if (!this.useIndexImprovability && !this.useIndexNovelty && this.useIndexInfeasibility) {
			//this is what I need
			return this.treePath.getIndexInfeasibility(entryPoint, path);
		} else {
			final int indexImprovability = this.treePath.getIndexImprovability(entryPoint, path);
	    	final int indexNovelty = this.treePath.getIndexNovelty(entryPoint, path);
	    	final int indexInfeasibility = this.treePath.getIndexInfeasibility(entryPoint, path);
	    	
			//detects the index that pass a threshold
			final boolean thresholdImprovability = indexImprovability > 0;
			final boolean thresholdNovelty = indexNovelty < 2;
			final boolean thresholdInfeasibility = indexInfeasibility > 1;

			//counts the passed thresholds
			final boolean[] useIndex = {this.useIndexImprovability, this.useIndexNovelty, this.useIndexInfeasibility};
			final boolean[] threshold = {thresholdImprovability, thresholdNovelty, thresholdInfeasibility};
			int count = 0;
			for (int i = 0; i < threshold.length; ++i) {
				if (useIndex[i] && threshold[i]) {
					++count;
				}
			}

			return count;
		}
    }

    /**
     * Updates the improvability index for a given path.
     * 
     * @param entryPoint a {@link String}, the 
     *        identifier of a method's entry point where the
     *        path starts. 
     * @param path a {@link List}{@code <}{@link Clause}{@code >}. 
     *        The first is the closest to the root, the last is the leaf.
     */
    private void updateIndexImprovability(String entryPoint, List<Clause> path) {
        final Set<String> branchesNeighbor = this.treePath.getBranchesNeighbor(entryPoint, path);
        if (branchesNeighbor == null) {
            throw new AssertionError("Attempted to update the improvability index of a path condition that was not yet inserted in the TreePath.");
        }
        final Set<String> branchesRelevant = filterOnPattern(branchesNeighbor, this.patternBranchesImprovability);
        for (Iterator<String> it  = branchesRelevant.iterator(); it.hasNext(); ) {
            if (this.treePath.covers(it.next())) {
            	it.remove();
            }
        }
        final int indexImprovability = Math.min(branchesRelevant.size(), INDEX_IMPROVABILITY_MAX);
        this.treePath.setIndexImprovability(entryPoint, path, indexImprovability);
    }

    /**
     * Updates the novelty index for a given path.
     * 
     * @param entryPoint a {@link String}, the 
     *        identifier of a method's entry point where the
     *        path starts. 
     * @param path a {@link List}{@code <}{@link Clause}{@code >}. 
     *        The first is the closest to the root, the last is the leaf.
     */
    private void updateIndexNovelty(String entryPoint, List<Clause> path) {
        final Set<String> branches = this.treePath.getBranchesCovered(entryPoint, path);
        if (branches == null) {
            throw new AssertionError("Attempted to update the novelty index of a path condition that was not yet inserted in the TreePath.");
        }
        final Map<String, Integer> hits = this.treePath.getHits(entryPoint, path);
        final Pattern p = Pattern.compile(this.patternBranchesNovelty);
        for (Iterator<Map.Entry<String, Integer>> it  = hits.entrySet().iterator(); it.hasNext(); ) {
        	final Map.Entry<String, Integer> hitEntry = it.next();
        	final Matcher m = p.matcher(hitEntry.getKey());
        	if (!m.matches()) {
        		it.remove();
        	}
        }
        final int minimum = (hits.values().isEmpty() ? INDEX_NOVELTY_MIN : Collections.min(hits.values()));
        final int indexNovelty = Math.min(minimum, INDEX_NOVELTY_MAX);
        this.treePath.setIndexNovelty(entryPoint, path, indexNovelty);
    }
    
    /**
     * Updates the infeasibility index for a given path.
     * 
     * @param entryPoint a {@link String}, the 
     *        identifier of a method's entry point where the
     *        path starts. 
     * @param path a {@link List}{@code <}{@link Clause}{@code >}. 
     *        The first is the closest to the root, the last is the leaf.
     */
    private void updateIndexInfeasibility(String entryPoint, List<Clause> path, boolean reclassifying) { //the third parameter is for logging purposes
    	//LOGGER.debug("Before getting bloomFilter");
        final BloomFilter bloomFilter = this.treePath.getBloomFilter(entryPoint, path);
        if (bloomFilter == null) {
            throw new AssertionError("Attempted to update the infeasibility index of a path condition that was not yet inserted in the TreePath.");
        }
        //LOGGER.debug("Before classifying");
        final ClassificationResult result = this.classifier.classify(bloomFilter, reclassifying);
        //LOGGER.debug("After classifying");
        final boolean unknown = result.isUnknown();
        final boolean feasible = result.getLabel();
        final int voting = result.getVoting();
        
        /*if (!reclassifying && !unknown) { //first time classifying this path and not unknown, since unknown classifications aren't stored
        	UtilClassificationLabels.setClassificationLabel(path, feasible);
        	//LOGGER.info("[updateIndexInfeasibility] Changed the classification label of a possible report to %b, PC = %s", feasible,
        	//		stringifyPostFrontierPathCondition(path));
        }*/
        
        //LOGGER.info("[updateIndexInfeasibility] Got offset = %d", offset);
        
        final int indexInfeasibility;
        if (unknown) {
        	//max priority
        	LOGGER.debug("[updateIndexInfeasibility] The result is unknown, so the queue is the one with max priority, at index offset = %d", offset);
        	indexInfeasibility = this.queueRanking[offset];
        } else {
        	//voting <= K
        		
        	//if the sub-array length is odd and the label is feasible then use the mid queue too,
        	//so for example if we have {4,3,2,1,0} and offset = 2 the possible queues are {2,1} for feasible classifications and {0} for infeasible ones, 
        	//to give more priority to feasible classifications
        			
        	final int numberOfPossibleQueues;
        	if (feasible && newQueueRankingLength % 2 == 1) {
        		numberOfPossibleQueues = (newQueueRankingLength / 2) + 1;
        	} else {
        		numberOfPossibleQueues = newQueueRankingLength / 2;
        	}
        	//how many queues are being considered
        	
        	final int newValue;
        	//if infeasible, how many positions to go back (from the last position)
        	//if feasible, how many positions to go forward (from the first position, which is 0 + offset)
        	
        	if (numberOfPossibleQueues == 1 || NUMBER_OF_POSSIBLE_VOTING == 1) {
        		LOGGER.debug("[updateIndexInfeasibility] There's only 1 possible queue or 1 possible voting value, so newValue = 0");
        		newValue = 0;
        	} else {
        		//LOGGER.info("[updateIndexInfeasibility] There are %d possible queues", numberOfPossibleQueues);
    			
            	//LOGGER.info("[updateIndexInfeasibility] Number of possible values for voting: %d", numberOfPossibleVoting);
            	
        		//LOGGER.info("[updateIndexInfeasibility] Applying a linear transformation");
                //linear transformation, rounding to the closest int
                				
                final int oldValue = voting;
                
                final int oldBottom = JBSEResultInputOutputBuffer.K / 2 + 1; //floor(K/2)+1
                
                final int oldTop = JBSEResultInputOutputBuffer.K; //the max value which voting can be
                //voting in [floor(K/2)+1, K]
                
                //if for example we have voting in [2, 3] and newValue in [0, 1] we want to associate 3 (= K) to 0 and not 1,
            	//so what we're doing is use the negative interval for newValue, so [-1, 0], and then consider the abs. value for the result,
                //to associate 2 to 1 and 3 to 0
                				
                final int newBottom = -1 * (numberOfPossibleQueues - 1); 
                //subtract 1 since indexing starts from 0, so if we have 2 queues it's [-1, 0] which are 2 values
                final int newTop = 0;
                //the result is in [-(numberOfPossibleQueues-1), 0] for the reason described above, and then only the absolute value is used
                				
                //there can't be a division by zero because it only happens
                //when K = 1 or 2 but that means numberOfPossibleVoting = 1
                //so this statement isn't reached
                				
                //I multiply the denominator by 1.0d to use floating point values and then round the result
                newValue = Math.abs((int) Math.round((oldValue - oldBottom) / (1.0d * oldTop - oldBottom) * (newTop - newBottom) + newBottom));
                //using the abs. value as described above
                
                /*LOGGER.info("[updateIndexInfeasibility] Transformed voting = %d in [%d, %d] to a new value = %d which is the abs. value of a number in [%d, 0]",
                		voting, oldBottom, oldTop, newValue, newBottom);*/
        	}
            		
            LOGGER.debug("[updateIndexInfeasibility] Going %s from the %s position %d times", 
            		!feasible ? "back" : "forward", !feasible ? "last" : "first (0 + offset)", newValue);
            
            indexInfeasibility = !feasible ? this.queueRanking[this.queueRanking.length - 1 - newValue]
            		: this.queueRanking[offset + newValue]; //if feasible, 0 + offset + newValue is used
        }
        
        LOGGER.debug("Got index infeasibility = %d", indexInfeasibility);
        
        /*if (indexInfeasibility != this.queueRanking[offset]) { //priority != max
        	final int oldIndexInfeasibility = this.treePath.getIndexInfeasibility(entryPoint, path);
        	if (oldIndexInfeasibility != indexInfeasibility) { //could be the same because this method is invoked in the class PerformerJBSE too
	        	LOGGER.info("[updateIndexInfeasibility, NOT MAX, unknown = %b, voting = %d, feasible = %b] Changed the infeasibility index from %d to %d, path = %s", 
	        			unknown, voting, feasible, oldIndexInfeasibility, indexInfeasibility, stringifyPostFrontierPathCondition(path));
        	}
        }*/
		
		
        this.treePath.setIndexInfeasibility(entryPoint, path, indexInfeasibility);
    }

    private void forAllQueuedItems(BiConsumer<Integer, JBSEResult> toDo) {
        for (int queue : this.queues.keySet()) {
            for (JBSEResult bufferedJBSEResult : this.queues.get(queue)) {
                toDo.accept(queue, bufferedJBSEResult);
            }
        }
    }                    
    
    private void forAllQueuedItemsToUpdateImprovability(BiConsumer<Integer, JBSEResult> toDo) {
        forAllQueuedItems((queue, bufferedJBSEResult) -> {
        	final String entryPoint = bufferedJBSEResult.getTargetMethodSignature();
            final List<Clause> pathCondition = bufferedJBSEResult.getPathConditionGenerated();
            final Set<String> toCompareBranches = this.treePath.getBranchesNeighbor(entryPoint, pathCondition);
            if (!Collections.disjoint(toCompareBranches, this.coverageSetImprovability)) {
                toDo.accept(queue, bufferedJBSEResult);
            }
        });
    }
    
    private void forAllQueuedItemsToUpdateNovelty(BiConsumer<Integer, JBSEResult> toDo) {
        forAllQueuedItems((queue, bufferedJBSEResult) -> {
        	final String entryPoint = bufferedJBSEResult.getTargetMethodSignature();
            final List<Clause> pathCondition = bufferedJBSEResult.getPathConditionGenerated();
            final Set<String> toCompareBranches = this.treePath.getBranchesCovered(entryPoint, pathCondition);
            if (!Collections.disjoint(toCompareBranches, this.coverageSetNovelty)) {
                toDo.accept(queue, bufferedJBSEResult);
            }
        });
    }
    
    //a few methods to manage the classificationLabels Map
    
    /*public final static class UtilClassificationLabels {
    	public static String getLabel(List<Clause> path) {
    		final String pathCondition = stringifyPostFrontierPathCondition(path);
    		if (!JBSEResultInputOutputBuffer.classificationLabels.containsKey(pathCondition)) {
    			return "UNKNOWN";
    		} else {
    			return JBSEResultInputOutputBuffer.classificationLabels.get(pathCondition) ? "FEASIBLE" : "INFEASIBLE";
    		}
    	}
    	
    	public static boolean setClassificationLabel(List<Clause> path, boolean label) {
    		final String pathCondition = stringifyPostFrontierPathCondition(path);
    		if (JBSEResultInputOutputBuffer.classificationLabels.containsKey(pathCondition)) {
    			//something went wrong because it already exists
    			return false;
    		}
    		JBSEResultInputOutputBuffer.classificationLabels.put(pathCondition, label);
    		return true;
    	}
    	
    	public static void removePathCondition(String pathCondition) {
    		JBSEResultInputOutputBuffer.classificationLabels.remove(pathCondition);
    	}
    	
    }*/
}

