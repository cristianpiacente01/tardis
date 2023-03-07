package tardis.implementation.data;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import jbse.mem.Clause;

//(why is there no javadoc description of the class?)

final class BloomFilter {
    /** Prime numbers for hash function calculation. */
    private static final int[] PRIME_NUMBERS = {7, 11, 13};

    /** The number of rows in the Bloom filter structure. */
    private static final int N_ROWS = 16;

    /** The number of columns in the Bloom filter structure. */
    private static final int N_COLUMNS = 64;
    
    /** The size of the filter as a vector */
    private static final int SIZE = N_ROWS * N_COLUMNS;

    /** The Bloom filter structure used only for the context */
    private final BitSet[] bloomFilterStructure;
    
    /** The specific (concrete) infeasibility core, the conditions are stored in an array of strings to avoid using two Bloom filter structures in total */
    private final String[] specificInfeasibilityCore;
    
    /** The general (abstract) infeasibility core, also stored in an array of strings */
    private final String[] generalInfeasibilityCore;
    
    /** The specific context in the form of array of strings just for logging purposes */
    private final String[] specificContext;


    BloomFilter(List<Clause> path) {
        this.bloomFilterStructure = new BitSet[N_ROWS];
        for (int i = 0; i < bloomFilterStructure.length; ++i){
            this.bloomFilterStructure[i] = new BitSet(N_COLUMNS);
        }
        
        final String[][] outputSliced = SlicingManager.slice(path);
        this.specificInfeasibilityCore = outputSliced[0];
        this.generalInfeasibilityCore = outputSliced[1];
        
        this.specificContext = outputSliced[2];
        
        final String[] generalContext = outputSliced[3];
        
        fillBloomFilterStructure(specificContext, generalContext);
    }

    private void fillBloomFilterStructure(String[] specificContext, String[] generalContext) {
        for (int i = 0; i < specificContext.length; ++i) {
            //applies different hash functions to the general and specific context conditions
            for (int j = 0; j < PRIME_NUMBERS.length; ++j) {
                final long hashGeneral = 31 * PRIME_NUMBERS[j] + generalContext[i].hashCode();
                final long hashSpecific = 31 * PRIME_NUMBERS[j] + specificContext[i].hashCode();
                final long hashToPositiveGeneral = hash(Math.abs(hashGeneral));
                final long hashToPositiveSpecific = hash(Math.abs(hashSpecific));
                //resize the hashes in the range of the dimension of the two-dimensional array
                final int indexGeneral = (int) (hashToPositiveGeneral % N_COLUMNS);
                final int indexSpecific = (int) (hashToPositiveSpecific % (N_ROWS - 1));
                //sets the bit corresponding to the general index on the first line to 1, then
                //sets the bit corresponding to the specific index on the column of the 
                //previous general bit to 1
                this.bloomFilterStructure[0].set(indexGeneral);
                this.bloomFilterStructure[indexSpecific + 1].set(indexGeneral);
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
    
    double jaccardDistance(BloomFilter other) {
    	//the (context) JACCARD DISTANCE, NOT the SIMILARITY COEFFICIENT which was returned in the old implementation!
        final double retVal;
        if (other == null) {
            retVal = 0.0d;
        } else {
            double both = 0.0d;
            double atLeastOne = 0.0d;
            for (int i = 0; i < SIZE; ++i) {
                if (get(i) == true && other.get(i) == true) {
                    ++both;
                } else if (get(i) != other.get(i)) {
                    ++atLeastOne;
                } //else, do nothing
            }
            retVal = both / (both + atLeastOne);
        }
        return 1.0d - retVal; //distance = 1 - similarity coefficient, this is the proper jaccard distance
    }
	
    boolean containsOtherCore(BloomFilter other, boolean specific) {
    	//check if this core contains other's core (concrete or abstract depending on the specific flag)
    	return specific ? Arrays.asList(this.specificInfeasibilityCore).containsAll(Arrays.asList(other.specificInfeasibilityCore))
    			: Arrays.asList(this.generalInfeasibilityCore).containsAll(Arrays.asList(other.generalInfeasibilityCore));
    }
    
    private boolean get(int n) {
        final int row = n % N_ROWS;
        final int col = n / N_ROWS;
        return this.bloomFilterStructure[row].get(col);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(this.bloomFilterStructure);
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
        if (!Arrays.equals(this.bloomFilterStructure, other.bloomFilterStructure)) {
            return false;
        }
        return true;
    }
    
    public String getSpecificCoreString() {
    	//used for logging purposes
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
    
    public String getSpecificContextString() {
    	//used for logging purposes
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
}
