package tardis.implementation.data;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import jbse.mem.Clause;

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
    
    /* TODO: remove the arrays of String below, they're still here only for logging purposes */
    
    /** The specific context string array, TODO remove this */
    private final String[] specificContextStrArray;
    
    /** The specific infeasibility core string array, TODO remove this */
    private final String[] specificInfeasibilityCoreStrArray;
    
    /* EXPERIMENTING, used for infeasible classifications */
    private final List<Integer> specificLastClauseIndexes = new ArrayList<>();
    private final List<Integer> generalLastClauseIndexes = new ArrayList<>();


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
                if (i == specificInfeasibilityCore.length - 1) {
                	specificLastClauseIndexes.add(indexSpecific);
                	generalLastClauseIndexes.add(indexGeneral);
                }
            }  
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
	
    double coreSimilarity(BloomFilter other, boolean specific, boolean itemLabel) {
    	final BitSet thisCore = specific ? this.specificInfeasibilityCore : this.generalInfeasibilityCore;
    	final BitSet otherCore = specific ? other.specificInfeasibilityCore : other.generalInfeasibilityCore;
    	
    	final double baseScore = specific ? 2.0d : 1.0d; //this is used only if there's a relation
    	
    	final double retVal; //if there's a relation, retVal is baseScore + ratio, otherwise 0.0d
    	
    	if (itemLabel) { 
    		//training item is feasible
    		
    		//check if this core contains ALL the clauses of other core, a strong relation
    		
    		final BitSet tmpIntersection = (BitSet) thisCore.clone(); //clone is needed because the "and" method modifies the BitSet
    		tmpIntersection.and(otherCore);
    		
    		if (tmpIntersection.equals(otherCore)) {
    			retVal = baseScore + 1.0d;
    			//if specific then 3.0d
    			//if general then 2.0d
    		} else {
    			retVal = 0.0d;
    		}
    		
    	} else {
    		//training item is infeasible
    		
    		//if last clause isn't in both cores then there's no relation
    		
    		final List<Integer> thisIndexes = specific ? this.specificLastClauseIndexes : this.generalLastClauseIndexes;
    		final List<Integer> otherIndexes = specific ? other.specificLastClauseIndexes : other.generalLastClauseIndexes;
    		
    		if (!thisIndexes.equals(otherIndexes)) {
    			retVal = 0.0d;
    		} else {
    			//let's count how many clauses are common, skipping the last one since we've already checked for it
    			
    			final int otherCardinality = otherCore.cardinality() - otherIndexes.size(); 
    			//number of bits set to true in the other core
    			//which don't correspond to the last clause
        		
        		double both = 0.0d; 
        		//how many bits set to true in the other core are also set to true in this core
        		//not considering the last clause
        		
        		for (int i = 0; i < CORE_LENGTH; ++i) {
        			if (otherCore.get(i) == true && !otherIndexes.contains(i)) { //i mustn't refer to the last clause
        				if (thisCore.get(i) & otherCore.get(i) == otherCore.get(i)) { //which means thisCore.get(i) == true
        					++both;
        				}
        			} //else do nothing
        		}
        		
        		final double ratio = both / otherCardinality;
        		
        		retVal = baseScore + ratio;
        		//if specific then it's in [2.0d, 3.0d]
        		//if general then it's in [1.0d, 2.0d]
    		}
    	}
    	return retVal;
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
