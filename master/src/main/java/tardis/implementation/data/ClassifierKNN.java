package tardis.implementation.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tardis.implementation.common.Util;
import tardis.implementation.evosuite.Pair;

/**
 * Class that predicts the possible label of a given path condition by comparing the
 * infeasibility core of the item to be classified 
 * with the infeasibility core of all the items in the training set;
 * to perform the classification, the Jaccard similarity coefficient is used in case 
 * there is more than 1 neighbor with the same score (average ratio).
 * 
 * @author Matteo Modonato
 * @author Pietro Braione
 * @author Cristian Piacente
 */
final class ClassifierKNN {
	private static final Logger LOGGER = LogManager.getFormatterLogger(ClassifierKNN.class);
	
    private final int k;
    private final HashSet<TrainingItem> trainingSet = new HashSet<>();
    
    //used for ground truthing
  	private static AtomicInteger correctClassifications = new AtomicInteger(0);
  	private static AtomicInteger totalClassifications = new AtomicInteger(0);
    
    public ClassifierKNN(int k) {
        this.k = k;
    }
    
    public void train(Set<TrainingItem> newTrainingSet) {
        this.trainingSet.addAll(newTrainingSet);
    }

    public ClassificationResult classify(BloomFilter query, boolean reclassifying) { //the second parameter is for logging purposes
    	LOGGER.info("[classify] Classifying query with\n"
    			+ "\t\t specific context: %s\n"
    			+ "\t\t specific core: %s", 
    			query.getSpecificContextString(), query.getSpecificCoreString());
    			
    	if (this.trainingSet.size() < k) {
    		LOGGER.info("[classify] The query was classified as UNKNOWN because trainingSet.size() is too small");
    		final ClassificationResult trainingSetTooSmallOutput = ClassificationResult.unknown();
    		return trainingSetTooSmallOutput;
    	}
    	
    	//used for ground truthing
    	boolean infeasibleExists = false; //true iff at least an infeasible item is in the training set
    	
    	//for every item in the training set, calculate the neighbor's similarity and store it with the label
    	final ArrayList<Neighbor> neighborRanking = new ArrayList<>();
    	
        for (TrainingItem item : this.trainingSet) {
        	//set infeasibleExists to true if there's an infeasible item
        	if (!infeasibleExists && !item.getLabel()) {
        		infeasibleExists = true;
        	}
        	
        	final BloomFilter itemBloomFilter = item.getBloomFilter();
        	
        	final boolean itemLabel = item.getLabel(); //this item's label
        	
        	//check if first contains second
        	
        	final BloomFilter first = itemLabel ? itemBloomFilter : query;
        	final BloomFilter second = itemLabel ? query : itemBloomFilter; 
        	
        	final Pair<Double, Double> similarity = first.calculateOtherSimilarity(second);
        	
        	neighborRanking.add(new Neighbor(similarity, itemLabel));
        }
        
        Collections.sort(neighborRanking, new NeighborComparator());
        
        //analyzes the top k elements and counts how many are
        //uncertain, and how many classify with each label
        int countClassifyFalse = 0;
        int countClassifyTrue = 0;
        for (int l = 0; l < this.k; ++l) {
            final boolean label = neighborRanking.get(l).label;
            final Pair<Double, Double> similarity = neighborRanking.get(l).similarity;
            if (Util.doubleEquals(similarity.first(), 0.0d)) { //there's no need to check for the context similarity too
            	//optimization, since when an uncertain is found then the remaining neighbors are uncertain too (because of the descending order)
                break;
            } else if (label) { 
                ++countClassifyTrue;
            } else { //!label
                ++countClassifyFalse;
            }
        }
        
        final int countUncertain = this.k - countClassifyTrue - countClassifyFalse; //optimization, this is now a final variable
        
        LOGGER.info("[classify] countUncertain = %d, countClassifyFalse = %d, countClassifyTrue = %d", countUncertain, countClassifyFalse, countClassifyTrue);

        //builds the output
        final ClassificationResult output;
        
        if ((countUncertain >= countClassifyFalse && countUncertain >= countClassifyTrue) || countClassifyFalse == countClassifyTrue) {
        	//too many uncertains, or tie between 0 and 1 classification
        	LOGGER.info("[classify] The query was classified as UNKNOWN because there are too many uncertains or there's a tie between false and true");
        	output = ClassificationResult.unknown();
        } else if (countClassifyTrue > countClassifyFalse && (countUncertain + countClassifyFalse < countClassifyTrue)) {
        	LOGGER.info("[classify] The query was classified as FEASIBLE");
        	output = ClassificationResult.of(true, countClassifyTrue);
        } else if (countClassifyFalse > countClassifyTrue && (countUncertain + countClassifyTrue < countClassifyFalse)) {
        	LOGGER.info("[classify] The query was classified as INFEASIBLE");
        	output = ClassificationResult.of(false, countClassifyFalse);
        } else { //e.g. K = 10 but countUncertain = 2 and countClassifyFalse = 3 so countClassifyTrue = 5 but it would make sense if this was > 5
        	LOGGER.info("[classify] The query was classified as UNKNOWN because the uncertains + the lowest counter isn't less than the highest one");
        	output = ClassificationResult.unknown();
        }
        
        if (!output.isUnknown() && infeasibleExists) {
        	//ground truthing for known classifications and there's at least an infeasible item
        	ClassifierKNN.groundTruthingClassification(query, output, reclassifying);
        }
        
        return output;
    }

    //not synchronized because there's only a classifier instance
	private static void groundTruthingClassification(BloomFilter query, ClassificationResult output, boolean reclassifying) { 
		//the third parameter is for logging purposes
		
		final int totalClassifications = ClassifierKNN.totalClassifications.incrementAndGet();
		
		final int correctClassifications;
		    
		final String pathCondition; 
		//I pass the whole PC to the method Util.calculateGroundTruth just to be coherent with TestDetector, 
		//even if I could just pass the core because I don't need to check the context too
		if (!query.getSpecificContextString().isEmpty()) {
		    //the context is not empty
		    pathCondition = query.getSpecificContextString() + " && " + query.getSpecificCoreString();
		} else {
		    //the context is empty
			pathCondition = query.getSpecificCoreString();
		}
		    
		final boolean groundTruth = Util.calculateGroundTruth(pathCondition);
		    
		if (output.getLabel() != groundTruth) {
		    //classification != ground truth
		    LOGGER.warn("[reclassifying = %b] GROUND TRUTH = %b, but the query was classified with LABEL = %b, PC = %s", reclassifying,
		    		groundTruth, output.getLabel(), pathCondition);
		    correctClassifications = ClassifierKNN.correctClassifications.get();
		} else {
		    correctClassifications = ClassifierKNN.correctClassifications.incrementAndGet();
		}
		    
		LOGGER.info("[classify] Correct classifications: %d/%d", correctClassifications, totalClassifications);
	}

    private static class Neighbor {
    	private final Pair<Double, Double> similarity; //<averageRatio, contextSimilarity>
    	private final boolean label;
    	private Neighbor(Pair<Double, Double> similarity, boolean label) {
    		this.similarity = similarity;
    		this.label = label;
    	}
    }

    private static class NeighborComparator implements Comparator<Neighbor> {
        @Override
        public int compare(Neighbor a, Neighbor b) {
        	
        	if (!Util.doubleEquals(a.similarity.first(), b.similarity.first())) { //averageRatio isn't the same
        		//descending order
        		return a.similarity.first() > b.similarity.first() ? -1 : 1;
        	} else {
        		//averageRatio is the same, use the context Jaccard similarity coefficient (descending order again)
        		return Util.doubleEquals(a.similarity.second(), b.similarity.second()) ? 0 
        				: (a.similarity.second() > b.similarity.second() ? -1 : 1);
        	}
        }
        
    }
    
    static class ClassificationResult {
        private static final ClassificationResult UNKNOWN = new ClassificationResult();
        
        private final boolean unknown;
        private final boolean label;
        private final int voting;
        //private final double averageDistance; //it wasn't even used in the old implementation...
        
        static ClassificationResult unknown() {
            return UNKNOWN;
        }
        
        static ClassificationResult of(boolean label, int voting) {
            return new ClassificationResult(label, voting);
        }
        
        private ClassificationResult() {
        	this.unknown = true;
        	this.label = false; //default value
        	this.voting = 0; //default value
        }
        
        private ClassificationResult(boolean label, int voting) {
            this.unknown = false;
            this.label = label;
            this.voting = voting;
        }
        
        public boolean isUnknown() {
            return this.unknown;
        }
        
        public boolean getLabel() {
            return this.label;
        }
        
        public int getVoting() {
            return this.voting;
        }
    }
}
