package tardis.implementation.data;

import java.util.BitSet;
import java.util.List;
import jbse.mem.Clause;

//TODO javadoc description

final class BloomFilter {
    /** Prime numbers for hash function calculation. */
    private static final int[] PRIME_NUMBERS = {7, 11, 13};

    /** The number of columns (length) in the BitSet used for storing the CONTEXT. */
    private static final int CTX_LENGTH = 32;
    
    /** The number of columns (length) in the BitSet used for storing the INFEASIBILITY CORE. */
    private static final int CORE_LENGTH = 64;
    
    /** The Bloom filter structure (only 1 BitSet) used for the context */
    private final BitSet context = new BitSet(CTX_LENGTH);
    
    /** The Bloom filter structure (only 1 BitSet) used for the infeasibility core */
    private final BitSet infeasibilityCore = new BitSet(CORE_LENGTH);
    
    /* TODO: remove the arrays of String below */
    
    /** The specific context, TODO remove this */
    private final String[] specificContext;
    
    /** The specific infeasibility core, TODO remove this */
    private final String[] specificInfeasibilityCore;
    
    /** The general infeasibility core, TODO remove this */
    private final String[] generalInfeasibilityCore;


    BloomFilter(List<Clause> path) {
        final String[][] outputSliced = SlicingManager.slice(path);
        
        final String[] specificInfeasibilityCore = outputSliced[0];
        this.specificInfeasibilityCore = specificInfeasibilityCore; //TODO remove this
        
        final String[] generalInfeasibilityCore = outputSliced[1];
        this.generalInfeasibilityCore = generalInfeasibilityCore; //TODO remove this
        
        final String[] specificContext = outputSliced[2];
        this.specificContext = specificContext; //TODO remove this
        
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
        
        //fill infeasibility core BitSet
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
                this.infeasibilityCore.set(indexGeneral);
                this.infeasibilityCore.set(indexSpecific);
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
	
    boolean containsOtherCore(BloomFilter other, boolean specific) {
    	//the problem is that in the BloomFilter class I don't store a BitSet for the specific/general core...
    	//TODO remove this, but what should I do instead if only the whole core is stored?
    	
    	final BitSet tmpOther = new BitSet(BloomFilter.CORE_LENGTH);
    	
    	//fill tmpOther
    	for (int i = 0; i < other.specificInfeasibilityCore.length; ++i) {
    		for (int j = 0; j < BloomFilter.PRIME_NUMBERS.length; ++j) {
    			
    			final long hashOther = specific ? (31 * PRIME_NUMBERS[j] + other.specificInfeasibilityCore[i].hashCode())
    					: (31 * PRIME_NUMBERS[j] + other.generalInfeasibilityCore[i].hashCode());
    			
                final long hashToPositive = hash(Math.abs(hashOther));
                final int index = (int)(hashToPositive % BloomFilter.CORE_LENGTH);
                tmpOther.set(index);
            }
    	}
    	
    	//this core AND other specific/general core = other specific/general core
    	final BitSet tmpIntersection = (BitSet) this.infeasibilityCore.clone(); //clone is needed because the "and" method modifies the BitSet
    	tmpIntersection.and(tmpOther);
        return tmpIntersection.equals(tmpOther);
    }
    
    @Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + this.context.hashCode();
		result = prime * result + this.infeasibilityCore.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final BloomFilter other = (BloomFilter) obj;
		if (context == null) {
			if (other.context != null)
				return false;
		} else if (!context.equals(other.context))
			return false;
		if (infeasibilityCore == null) {
			if (other.infeasibilityCore != null)
				return false;
		} else if (!infeasibilityCore.equals(other.infeasibilityCore))
			return false;
		return true;
	}
    
	//TODO remove this, used for logging purposes
    public String getSpecificContextString() {
    	String retVal = "";
    	for (String str : specificContext) {
    		if (retVal.isEmpty()) {
    			retVal = str;
    		}
    		else {
    			retVal += " && " + str;
    		}
    	}
    	return retVal;
    }
    
    //TODO remove this, used for logging purposes
	public String getSpecificCoreString() {
    	String retVal = "";
    	for (String str : specificInfeasibilityCore) {
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
