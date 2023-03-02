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
 * infeasibility core (specific and abstract if needed) of the item to be classified 
 * with the infeasibility core of all the items in the training set:
 * if there's no relation then the Jaccard distance between the contexts is used 
 * (via a Bloom filter structure) to perform the classification 
 * and calculate the infeasibility index.
 * 
 * @author Matteo Modonato
 * @author Pietro Braione
 * @author Cristian Piacente
 */
final class ClassifierKNN {
	private static final Logger LOGGER = LogManager.getFormatterLogger(ClassifierKNN.class);
	
    private final int k; //currently not used
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
    		return ClassificationResult.unknown(); //too small
    	}
    	
    	//for every item in the training set, calculate the neighbor's ranking (floating point value) and store it + the label and the BloomFilter instance
    	final ArrayList<Neighbor> neighbors = new ArrayList<>();
    	
    	BloomFilter first = null, second = null; //we'll check if first contains second (considering their cores)
    	//they're declared here so we can reuse the same variables
    	
        for (TrainingItem item : this.trainingSet) {
        	final BloomFilter itemBloomFilter = item.getBloomFilter(); //item's BloomFilter structure, used to check for a relation
        	
        	final double ranking; //can be 0.0 or 1.0<=ranking<2.0 or 2.0<=ranking<3.0
        	
        	final double averageDistance = query.jaccardDistance(itemBloomFilter); //context distance
        	
        	//as said before, if first's core contains second's core we have a relation
        	first = null;
        	second = null;
        	
        	if (item.getLabel()) {
        		//if the item is feasible, we have a relation if
        		//the item contains the query
        		
        		//e.g. we have A && B && C && D feasible in the training set
        		//and our query is A && B && C
        		
        		first = itemBloomFilter;
        		second = query;
        	}
        	else {
        		//if the item is infeasible, we have a relation if
        		//the query contains the item
        		
        		//e.g. we have A && B && C infeasible in the training set
        		//and our query is A && B && C && D
        		
        		first = query;
        		second = itemBloomFilter;
        	}
        	
        	if (first.containsOtherCore(second, true)) { //specific (concrete) core
        		ranking = 2.0d + (1.0d - averageDistance); //2 + similarity coefficient
        	} 
        	else if (first.containsOtherCore(second, false)) { //general (abstract) core
        		ranking = 1.0d + (1.0d - averageDistance); //1 + similarity coefficient
        	}
        	else {
        		ranking = 0.0d;
        	}
        	
        	neighbors.add(new Neighbor(ranking, item.getLabel(), itemBloomFilter));
        }
        
        //infeasible first, then higher ranking first (descending order)
        Collections.sort(neighbors, new NeighborComparator());
        
        Neighbor topNeighborInfeasible = neighbors.get(0);
        
        //if the label is true it means there's no infeasible neighbor
        //if this is the case or the top infeasible's ranking is 0.0 (there's no core relation) then return UNKNOWN
        if (topNeighborInfeasible.label || Util.doubleEquals(topNeighborInfeasible.ranking, 0.0d)) { 
        	//there is no top neighbor infeasible or ranking equals 0
        	LOGGER.info("[classify] The query was classified as UNKNOWN because there's no topNeighborInfeasible or its ranking is 0.0");
        	return ClassificationResult.unknown();
        }
        
        
        Neighbor topNeighborFeasible = null;
        
        //search for the top neighbor feasible if it exists (it should always exist)
        for (int i = 1; i < neighbors.size(); ++i) {
        	if (neighbors.get(i).label) {
        		topNeighborFeasible = neighbors.get(i);
        		break;
        	}
        }
        
        //assert(topNeighborFeasible != null);
        
    	//let's compare the top neighbor infeasible and the top neighbor feasible, both exist
        
        LOGGER.info("[classify] Found both topNeighborInfeasible and topNeighborFeasible");
        
        if (topNeighborInfeasible.ranking > topNeighborFeasible.ranking) {
        	LOGGER.info("[classify] topNeighborInfeasible's ranking is greater, the query was classified as INFEASIBLE with ranking = %d", (int)topNeighborInfeasible.ranking);
        	return ClassificationResult.of(false, (int)topNeighborInfeasible.ranking);
        }
        else {
        	LOGGER.info("[classify] topNeighborFeasible's ranking is greater (FEASIBLE) or equal (UNKNOWN), the query was classified as FEASIBLE (ranking = 3, the same as UNKNOWN)");
        	return ClassificationResult.of(true, 3); //feasible (or unknown)
        }
    }

    private static class Neighbor {
    	private final double ranking; //the distance isn't needed because it's 1 - the decimal part
    	private final boolean label; //true iff feasible
    	private final BloomFilter bloomFilter; //needed in the classify method
        
    	private Neighbor(double ranking, boolean label, BloomFilter bloomFilter) {
    		this.ranking = ranking;
    		this.label = label;  	    
    		this.bloomFilter = bloomFilter;
    	}
    }

    private static class NeighborComparator implements Comparator<Neighbor> {
        @Override
        public int compare(Neighbor a, Neighbor b) {
        	//let's put the neighbors labeled as infeasible first, so it's easier for the classify method
        	
        	if (a.label != b.label) {
        		return !a.label ? -1 : 1;
        		//if a.label is false then b.label must be true
        		//if a.label is true then b.label must be false
        	}
        	
        	
        	//here we have the same label, let's check the ranking
        	
        	boolean sameDistance = Util.doubleEquals(a.ranking, b.ranking);
        	
        	//descending order to compare the ranking, the higher the better
        	return sameDistance ? 0 : (a.ranking > b.ranking ? -1 : 1); 
        }
    }
    
    static class ClassificationResult {
        private static final ClassificationResult UNKNOWN = new ClassificationResult();
        //in this implementation, unknown and feasible (ranking = 3) are the same
        
        private final boolean unknown; //true iff unknown but unknown is true iff ranking = 3...
        //in the future we could reduce the number of attributes
        
        private final boolean label; //true iff feasible
        //private final int voting; //no voting for now
        private final int ranking; //1 or 2 or 3 (never 0 because unknown is 3 now)
        
        //private final double averageDistance; //the average distance wasn't even used in the old implementation...
        
        static ClassificationResult unknown() {
            return UNKNOWN;
        }
        
        static ClassificationResult of(boolean label, int ranking) {
        	//assert(ranking != 0);
            return new ClassificationResult(false, label, ranking);
        }
        
        private ClassificationResult() {
        	this(true, true, 3); //unknown is considered as feasible now
        }
        
        private ClassificationResult(boolean unknown, boolean label, int ranking) {
            this.unknown = unknown;
            this.label = label;
            this.ranking = ranking;
        }
        
        public boolean isUnknown() {
            return this.unknown; //as said before, we could just return this.ranking == 3;
        }
        
        public boolean getLabel() {
            return this.label;
        }
        
        public int getRanking() {
            return this.ranking;
        }
    }
}
