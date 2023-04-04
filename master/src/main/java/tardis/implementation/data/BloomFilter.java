package tardis.implementation.data;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import jbse.mem.Clause;
import tardis.implementation.evosuite.Pair;

//TODO javadoc description

final class BloomFilter {
    /** Prime numbers for hash function calculation. */
    private static final int[] PRIME_NUMBERS = {7, 11, 13};

    /** The number of columns (LENGTH) in the BitSet used for storing the CONTEXT. */
    private static final int CTX_LENGTH = 64;
    
    /** The number of columns (LENGTH) in the BitSet used for storing the INFEASIBILITY CORE. */
    private static final int CORE_LENGTH = 128;
    
    /** The only Bloom filter structure (only 1 BitSet) used for the context, for both specific and general */
    private final BitSet context = new BitSet(CTX_LENGTH);
    
    /** A Bloom filter structure (1 BitSet), used for the SPECIFIC infeasibility core */
    private final BitSet specificInfeasibilityCore = new BitSet(CORE_LENGTH);
    
    /** A Bloom filter structure (only 1 BitSet), used for the GENERAL infeasibility core */
    private final BitSet generalInfeasibilityCore = new BitSet(CORE_LENGTH);
    
    /** A List of BloomFilter where each one singularly represents a clause of the core */
    private final List<BloomFilter> coreBloomFilters = new ArrayList<>();
    
    /* TODO: remove the arrays of String below, they're still here only for logging purposes */
    
    /** The specific context string array, TODO remove this */
    private String[] specificContextStrArray;
    
    /** The specific infeasibility core string array, TODO remove this */
    private String[] specificInfeasibilityCoreStrArray;


    BloomFilter(List<Clause> path) {
        final String[][] outputSliced = SlicingManager.slice(path);
        
        final String[] specificInfeasibilityCore = outputSliced[0];
        this.specificInfeasibilityCoreStrArray = specificInfeasibilityCore; //TODO remove this
        
        final String[] generalInfeasibilityCore = outputSliced[1];
        
        final String[] specificContext = outputSliced[2];
        this.specificContextStrArray = specificContext; //TODO remove this
        
        final String[] generalContext = outputSliced[3];
        
        fillBloomFilterStructure(specificContext, generalContext, specificInfeasibilityCore, generalInfeasibilityCore);
    }

    private void fillBloomFilterStructure(String[] specificContext, String[] generalContext,
    		String[] specificInfeasibilityCore, String[] generalInfeasibilityCore) {
        
        //fill context BitSet
        for (int i = 0; i < specificContext.length; ++i) {
            //apply different hash functions for each clause
            for (int j = 0; j < PRIME_NUMBERS.length; ++j) {
                final long hashGeneral = 31 * PRIME_NUMBERS[j] + generalContext[i].hashCode();
                final long hashSpecific = 31 * PRIME_NUMBERS[j] + specificContext[i].hashCode();
                final long hashToPositiveGeneral = hash(Math.abs(hashGeneral));
                final long hashToPositiveSpecific = hash(Math.abs(hashSpecific));
                final int indexGeneral = (int)(hashToPositiveGeneral % CTX_LENGTH);
                final int indexSpecific = (int)(hashToPositiveSpecific % CTX_LENGTH);
                //sets the bit corresponding to the general index to 1 and then
                //sets the bit corresponding to the specific index to 1
                this.context.set(indexGeneral);
                this.context.set(indexSpecific);
            }  
        }
        
        //fill the two infeasibility core BitSets
        for (int i = 0; i < specificInfeasibilityCore.length; ++i) {
        	
        	final BloomFilter tmp = new BloomFilter(new ArrayList<>()); 
        	//empty, there's no recursion because with an empty List of clauses we have specificInfeasibilityCore.length = 0,
        	//so this loop is never executed when creating tmp
        	
        	tmp.specificInfeasibilityCoreStrArray = new String[] {specificInfeasibilityCore[i]}; //TODO remove this
        	
            //apply different hash functions for each clause
            for (int j = 0; j < PRIME_NUMBERS.length; ++j) {
                final long hashGeneral = 31 * PRIME_NUMBERS[j] + generalInfeasibilityCore[i].hashCode();
                final long hashSpecific = 31 * PRIME_NUMBERS[j] + specificInfeasibilityCore[i].hashCode();
                final long hashToPositiveGeneral = hash(Math.abs(hashGeneral));
                final long hashToPositiveSpecific = hash(Math.abs(hashSpecific));
                final int indexGeneral = (int) (hashToPositiveGeneral % CORE_LENGTH);
                final int indexSpecific = (int) (hashToPositiveSpecific % CORE_LENGTH);
                //sets the bit corresponding to the general index to 1 and then
                //sets the bit corresponding to the specific index to 1
                this.generalInfeasibilityCore.set(indexGeneral);
                this.specificInfeasibilityCore.set(indexSpecific);
                
                tmp.generalInfeasibilityCore.set(indexGeneral);
                tmp.specificInfeasibilityCore.set(indexSpecific);
                
            }  
            
            this.coreBloomFilters.add(tmp);
        }
        
    }

    /**
     * Applies a supplemental hash function to a given hashCode, which defends
     * against poor quality hash functions and tries to reduce collisions.
     */
    private static long hash(long h) {
        //source: https://hg.openjdk.java.net/jdk7/jdk7/jdk/file/9b8c96f96a0f/src/share/classes/java/util/HashMap.java#l264
        h ^= (h >>> 20) ^ (h >>> 12);
        return h ^ (h >>> 7) ^ (h >>> 4);
    }
    
    double ctxJaccardSimilarity(BloomFilter other) {
    	//the context Jaccard similarity coefficient
        final double retVal;
        if (other == null) {
            retVal = 0.0d;
        } else {
            double both = 0.0d;
            double atLeastOne = 0.0d;
            for (int i = 0; i < CTX_LENGTH; ++i) {
                if (this.context.get(i) == true && other.context.get(i) == true) {
                    ++both;
                } else if (this.context.get(i) != other.context.get(i)) {
                    ++atLeastOne;
                } //else, do nothing
            }
            retVal = both / (both + atLeastOne);
        }
        return retVal;
    }
    
    Pair<Double, Double> calculateOtherSimilarity(BloomFilter other) {
    	if (this.coreBloomFilters.size() == 0 || other.coreBloomFilters.size() == 0) {
    		return new Pair<Double, Double>(0.0d, 0.0d); //nothing to do here
    	}
		
		final BloomFilter thisCoreLastClause = this.coreBloomFilters.get(this.coreBloomFilters.size() - 1);
		final BloomFilter otherCoreLastClause = other.coreBloomFilters.get(other.coreBloomFilters.size() - 1);
		
		//the last general clause isn't the same, no relation
		if (!thisCoreLastClause.generalInfeasibilityCore.equals(otherCoreLastClause.generalInfeasibilityCore)) {
			return new Pair<Double, Double>(0.0d, 0.0d);
		}
		
		double specificCount = 0.0d;
		double generalCount = 0.0d;
		//count how many clauses are common
		
		final int size = other.coreBloomFilters.size();
		//how many clauses can be common in total, since this core has to contain other core
		
		for (BloomFilter otherClause : other.coreBloomFilters) {
			//check if this core contains otherClause
			//in both cases specific and general
			
			//this specific core AND other specific clause = other specific clause
	    	final BitSet specificIntersection = (BitSet) this.specificInfeasibilityCore.clone();
	    	specificIntersection.and(otherClause.specificInfeasibilityCore);
	        if (specificIntersection.equals(otherClause.specificInfeasibilityCore)) {
	        	++specificCount;
	        }
	        
	        //this general core AND other general clause = other general clause
	    	final BitSet generalIntersection = (BitSet) this.generalInfeasibilityCore.clone();
	    	generalIntersection.and(otherClause.generalInfeasibilityCore);
	        if (generalIntersection.equals(otherClause.generalInfeasibilityCore)) {
	        	++generalCount;
	        }
	        
		}
		
		//adjusted specific and general ratio
		
		final double specificRatio;
		if (specificCount / size > 1.0d) {
			specificRatio = 1.0d;
		} else {
			specificRatio = specificCount / size;
		}
				
		final double generalRatio;
		if (generalCount / size > 1.0d) {
			generalRatio = 1.0d;
		} else {
			generalRatio = generalCount / size;
		}
		
		//return the average ratio (never 0.0d, since at least the last general clause is the same) and the context Jaccard similarity
		
		return new Pair<Double, Double>(((specificRatio + generalRatio) / 2.0d), this.ctxJaccardSimilarity(other));
	}
    
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + context.hashCode();
		result = prime * result + generalInfeasibilityCore.hashCode();
		result = prime * result + specificInfeasibilityCore.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final BloomFilter other = (BloomFilter) obj;
		if (!context.equals(other.context)) {
			return false;
		}
		if (!generalInfeasibilityCore.equals(other.generalInfeasibilityCore)) {
			return false;
		}
		if (!specificInfeasibilityCore.equals(other.specificInfeasibilityCore)) {
			return false;
		}
		return true;
	}

	//TODO remove this, it's only used for logging purposes
    public String getSpecificContextString() {
    	String retVal = "";
    	for (String str : specificContextStrArray) {
    		if (retVal.isEmpty()) {
    			retVal = str;
    		}
    		else {
    			retVal += " && " + str;
    		}
    	}
    	return retVal;
    }
    
    //TODO remove this, it's only used for logging purposes
	public String getSpecificCoreString() {
    	String retVal = "";
    	for (String str : specificInfeasibilityCoreStrArray) {
    		if (retVal.isEmpty()) {
    			retVal = str;
    		}
    		else {
    			retVal += " && " + str;
    		}
    	}
    	return retVal;
    }
}
