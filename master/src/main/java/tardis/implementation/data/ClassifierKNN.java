package tardis.implementation.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tardis.implementation.common.Util;

/**
 * Class that predicts the possible label of a given path condition by comparing the
 * infeasibility core of the item to be classified 
 * with the infeasibility core of all the items in the training set:
 * if there's no relation then the Jaccard distance between the contexts is used 
 * to perform the classification based on a similarity score.
 * 
 * @author Matteo Modonato
 * @author Pietro Braione
 * @author Cristian Piacente
 */
final class ClassifierKNN {
	private static final Logger LOGGER = LogManager.getFormatterLogger(ClassifierKNN.class);
	
    private final int k;
    private final HashSet<TrainingItem> trainingSet = new HashSet<>();
    
    public ClassifierKNN(int k) {
        this.k = k;
    }
    
    public void train(Set<TrainingItem> newTrainingSet) {
        this.trainingSet.addAll(newTrainingSet);
    }

    public ClassificationResult classify(BloomFilter query) {
    	LOGGER.info("[classify] Classifying query with\n"
    			+ "\t\t specific context: %s\n"
    			+ "\t\t specific core: %s", 
    			query.getSpecificContextString(), query.getSpecificCoreString());
    			
    	if (this.trainingSet.size() < k) {
    		LOGGER.info("[classify] The query was classified as UNKNOWN because trainingSet.size() is too small");
    		final ClassificationResult trainingSetTooSmallOutput = ClassificationResult.unknown();
    		return trainingSetTooSmallOutput;
    	}
    	
    	//for every item in the training set, calculate the neighbor's similarity score and store it with the label
    	final ArrayList<Neighbor> neighborRanking = new ArrayList<>();
    	
        for (TrainingItem item : this.trainingSet) {
        	final BloomFilter itemBloomFilter = item.getBloomFilter();
        	
        	final double similarity; 
        	
        	final double ctxSimilarity = query.ctxJaccardSimilarity(itemBloomFilter); //context Jaccard similarity coefficient
        	
        	/*
        	 * if the item is feasible, we have a relation if
        	 * the item contains the query, otherwise if the query contains the item
        	 * 
        	 * e.g. we have A && B && C && D feasible in the training set
        	 * and our query is A && B && C
        	 * 
        	 * e.g. we have A && B && C infeasible in the training set
        	 * and our query is A && B && C && D
        	*/
        	
        	
        	//we'll check if first contains second
        	
        	final BloomFilter first = item.getLabel() ? itemBloomFilter : query;
        	final BloomFilter second = item.getLabel() ? query : itemBloomFilter; 
        	
        	//if first's core contains second's core we have a relation
        	//if there's no relation between the specific cores then the general cores are checked too
        	
        	if (first.containsOtherCore(second, true)) { //specific (concrete) cores
        		similarity = 2.0d + ctxSimilarity;
        	} 
        	else if (first.containsOtherCore(second, false)) { //general (abstract) cores
        		similarity = 1.0d + ctxSimilarity;
        	}
        	else {
        		similarity = 0.0d;
        	}
        	
        	neighborRanking.add(new Neighbor(similarity, item.getLabel()));
        }
        
        Collections.sort(neighborRanking, new NeighborComparator());
        
        //analyzes the top k elements and counts how many are
        //uncertain, and how many classify with each label
        int countClassifyFalse = 0;
        int countClassifyTrue = 0;
        for (int l = 0; l < this.k; ++l) {
            final boolean label = neighborRanking.get(l).label;
            final double similarity = neighborRanking.get(l).similarity;
            if (Util.doubleEquals(similarity, 0.0d)) {
            	//optimization, since when a 0 is found then the remaining neighbors' similarities are 0 too (because of the descending order)
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
        
        //print if the output label and the ground truth aren't the same
        
        final String pathCondition; 
        //I pass the whole PC to the method Util.calculateGroundTruth just to be coherent with TestDetector, 
        //even if I could just pass the core because I don't need to check the context too
        if (!query.getSpecificContextString().trim().isEmpty()) {
        	//the context is not empty
        	pathCondition = query.getSpecificContextString() + " && " + query.getSpecificCoreString();
        } else {
        	//the context is empty
        	pathCondition = query.getSpecificCoreString();
        }
        
        final boolean groundTruth = Util.calculateGroundTruth(pathCondition);
        
        if (output.getLabel() != groundTruth) {
        	//classification != ground truth
        	LOGGER.warn("GROUND TRUTH = %b, but the query was classified with LABEL = %b, PC = %s", groundTruth, output.getLabel(), pathCondition);
        }
        
        
        return output;
    }

    private static class Neighbor {
    	private final double similarity;
    	private final boolean label;
    	private Neighbor(double similarity, boolean label) {
    		this.similarity = similarity;
    		this.label = label;
    	}
    }

    private static class NeighborComparator implements Comparator<Neighbor> {
        @Override
        public int compare(Neighbor a, Neighbor b) {
        	
        	boolean sameScore = Util.doubleEquals(a.similarity, b.similarity);
        	
        	//descending order
        	return sameScore ? 0 : (a.similarity > b.similarity ? -1 : 1); 
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
