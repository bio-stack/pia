package de.mpc.pia.modeller.protein;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import de.mpc.pia.intermediate.Accession;
import de.mpc.pia.intermediate.AccessionOccurrence;
import de.mpc.pia.modeller.peptide.ReportPeptide;
import de.mpc.pia.modeller.psm.PSMReportItem;
import de.mpc.pia.modeller.psm.ReportPSM;
import de.mpc.pia.modeller.psm.ReportPSMSet;
import de.mpc.pia.modeller.report.filter.Filterable;
import de.mpc.pia.modeller.score.FDRComputable;
import de.mpc.pia.modeller.score.ScoreModel;
import de.mpc.pia.modeller.score.ScoreModelEnum;
import de.mpc.pia.modeller.score.FDRData.DecoyStrategy;
import de.mpc.pia.modeller.score.comparator.Rankable;
import de.mpc.pia.webgui.proteinviewer.ProteinViewer;


/**
 * This class holds the information of a protein, as it will be reported in the
 * {@link ProteinViewer}
 * 
 * @author julian
 *
 */
public class ReportProtein implements Rankable, Filterable, FDRComputable {
	
	/** identifier for the peptide, for internal use only */
	private Long ID;
	
	/** the rank of this peptide (if calculated) */
	private Long rank;
	
	/** the protein score */
	private ScoreModel score;
	
	/** marks, if this Protein is a decoy */
	private boolean isDecoy;
	
	/** the local fdr of the PSM */
	private Double fdrValue;
	
	/** the q-value from FDR calculation */
	private Double qValue;
	
	/** marks, if this item is FDR good */
	private Boolean isFDRGood;
	
	/** the map of accessions */
	private Map<String, Accession> accMap;
	
	/** the key to the representative accession */
	private String representativeRef;
	
	/** maps from the peptide's stringID to the {@link ReportPeptide} */
	private Map<String, ReportPeptide> peptideMap;
	
	/** list of proteins, which consist of a subset of this protein's PSMs */
	private List<ReportProtein> subSetProteins;
	
	/** 
	 * This maps from the accession string to the corresponding coverage map,
	 * a map containing the sets of the covered parts of the sequence, mapping
	 * from the start to the end of a covered part. As it is a TreeMap, it is
	 * always sorted by the start positions.
	 */
	private HashMap<String, TreeMap<Integer, Integer>> coverageMaps;
	
	/** maps from the accession to the coverage, if null, it needs to be calculated*/
	private HashMap<String, Double> coverages;
	
	
	/** logger for this class */
	private static final Logger logger = Logger.getLogger(ReportProtein.class);
	
	
	public ReportProtein(Long id) {
		this.ID = id;
		rank = null;
		score = new ScoreModel(Double.NaN, ScoreModelEnum.PROTEIN_SCORE);
		isDecoy = false;
		fdrValue = Double.POSITIVE_INFINITY;
		qValue = Double.POSITIVE_INFINITY;
		isFDRGood = false;
		accMap = new TreeMap<String, Accession>();
		peptideMap = new TreeMap<String, ReportPeptide>();
		subSetProteins = new ArrayList<ReportProtein>(1);
		
		// the coverage maps will be initialised while adding accessions
		coverageMaps = new HashMap<String, TreeMap<Integer, Integer>>();
		// the coverages will be initialised when they are called
		coverages = new HashMap<String, Double>();
		
		representativeRef = null;
	}
	
	
	/**
	 * Returns the identifier for this protein.
	 * 
	 * @return
	 */
	public Long getID() {
		return ID;
	}
	
	
	/**
	 * Returns the representative accession of this protein.
	 * 
	 * @return
	 */
	public Accession getRepresentative() {
		return accMap.get(representativeRef);
	}
	
	
	/**
	 * Returns a List of the accessions.
	 *  
	 * @return
	 */
	public List<Accession> getAccessions() {
		return new ArrayList<Accession>(accMap.values());
	}
	
	
	/**
	 * Adds the given accession to the accessions map, if it is not yet in it.
	 * 
	 * @param acc
	 */
	public void addAccession(Accession acc) {
		String accession = acc.getAccession();
		if (!accMap.containsKey(accession)) {
			accMap.put(accession, acc);
			
			for (ReportPeptide pep : peptideMap.values()) {
				addPeptideToCoverage(accession, pep);
			}
			
			// set a representative (the first accession of the protein)
			if (representativeRef == null) {
				representativeRef = accession;
			}
		}
	}
	
	
	/**
	 * returns a List of the peptides.
	 * 
	 * @return
	 */
	public List<ReportPeptide> getPeptides() {
		return new ArrayList<ReportPeptide>(peptideMap.values());
	}
	
	
	/**
	 * Puts the given peptide with its stringID into the peptide map, if there
	 * is no peptide with this key in the map yet.
	 * 
	 * @param acc
	 */
	public void addPeptide(ReportPeptide pep) {
		if(!peptideMap.containsKey(pep.getStringID())) {
			peptideMap.put(pep.getStringID(), pep);
			
			addPeptideToAllCoverages(pep);
		}
	}
	
	
	/**
	 * Adds a peptide to the coverage map of the given accession.
	 * 
	 * @param pep
	 */
	private void addPeptideToCoverage(String accession, ReportPeptide pep) {
		TreeMap<Integer, Integer> coverageMap =
				coverageMaps.get(accession);
		if (coverageMap == null) {
			coverageMap = new TreeMap<Integer, Integer>();
			coverageMaps.put(accession, coverageMap);
		}
		
		String sequence = accMap.get(accession).getDbSequence();
		if (sequence == null) {
			// not all accessions have sequences
			return;
		}
		
		// the start and stop of this peptide
		for (AccessionOccurrence occurrence
				: pep.getPeptide().getAccessionOccurrences()) {
			
			if (accession.equals(occurrence.getAccession().getAccession())) {
				int start = occurrence.getStart();
				int end = occurrence.getEnd();
				
				
				// get the closest start before this start
				Integer startKey = coverageMap.floorKey(start);
				// get the closest start before this end
				Integer endKey = coverageMap.floorKey(end);
				
				
				if ((startKey != null) && (start <= coverageMap.get(startKey) + 1)) {
					// the start is in a coverage or directly adjacent to it, so take this as start
					start = startKey;
				}
				
				if ((endKey != null) && (end < coverageMap.get(endKey))) {
					// the end is in a coverage, take the bigger end
					end = coverageMap.get(endKey);
				} else {
					Integer nextKey = coverageMap.floorKey(end+1);
					if ((nextKey != null) &&
							((endKey == null) || (nextKey > endKey))) {
						end = coverageMap.get(nextKey);
					}
				}
				
				// remove all coverages between start and end
				Set<Integer> remKeys =
						new HashSet<Integer>(
								coverageMap.subMap(start, true, end, true).keySet());
				for (Integer key : remKeys) {
					coverageMap.remove(key);
				}
				
				// and finally add the start and end position
				coverageMap.put(start, end);
			}
		}
		
		// the coverage must be recalculated at the next call
		coverages.remove(accession);
	}
	
	
	/**
	 * Calls {@link #addPeptideToCoverage(String, ReportPeptide)} for all
	 * accessions of this {@link ReportProtein#}.
	 * @param pep
	 */
	private void addPeptideToAllCoverages(ReportPeptide pep) {
		for (String accession : accMap.keySet()) {
			addPeptideToCoverage(accession, pep);
		}
	}
	
	
	/**
	 * This function clears all the peptides in the map.
	 */
	public void clearPeptides() {
		peptideMap.clear();
		coverageMaps.clear();
		coverages.clear(); 
	}
	
	
	/**
	 * Getter for the number of spectra.
	 * @return
	 */
	public Integer getNrSpectra() {
		Set<String> spectraIdentificationKeys = new HashSet<String>();
		
		for (ReportPeptide pep : peptideMap.values()) {
			spectraIdentificationKeys.addAll(pep.getSpectraIdentificationKeys());
		}
		return spectraIdentificationKeys.size();
	}
	
	
	/**
	 * Getter for the number of PSMs.
	 * @return
	 */
	public Integer getNrPSMs() {
		int nrPSMs = 0;
		
		for (ReportPeptide pep : peptideMap.values()) {
			nrPSMs += pep.getNrPSMs();
		}
		
		return nrPSMs;
	}
	
	
	/**
	 * Getter for the number of peptides.
	 * @return
	 */
	public Integer getNrPeptides() {
		return peptideMap.size();
	}
	
	
	/**
	 * Setter for the score.
	 * @param score
	 */
	public void setScore(Double score) {
		this.score.setValue(score);
	}
	
	
	/**
	 * Getter for the protein score.
	 * @return
	 */
	public Double getScore() {
		return score.getValue();
	}
	
	
	/**
	 * Getter for the protein coverage of the given accession
	 * @return
	 */
	public Double getCoverage(String accession) {
		Double coverage = coverages.get(accession);
		if (coverage == null) {
			calculateCoverage(accession);
			coverage = coverages.get(accession);
		}
		
		return coverage;
	}
	
	
	/**
	 * Getter for the coverage map of the given accession
	 * @return
	 */
	public TreeMap<Integer, Integer> getCoverageMap(String accession) {
		return coverageMaps.get(accession);
	}
	
	
	/**
	 * Calculates the coverage for the given accession.
	 * @param accession
	 */
	private void calculateCoverage(String accession) {
		TreeMap<Integer, Integer> coverageMap = coverageMaps.get(accession);
		
		if (coverageMap == null) {
			// there is no coverage, maybe no sequence given
			coverages.put(accession, Double.NaN);
			return;
		}
		
		String dbSeq = accMap.get(accession).getDbSequence();
		if (dbSeq == null) {
			coverages.put(accession, Double.NaN);
			return;
		}
		
		Integer sequenceLength = dbSeq.length();
		
		Integer coveredAminoAcids = 0;
		for (Map.Entry<Integer, Integer> mapIt : coverageMap.entrySet()) {
			coveredAminoAcids += mapIt.getValue() - mapIt.getKey() + 1;
		}
		
		Double coverage = (double)coveredAminoAcids / (double)sequenceLength;
		coverages.put(accession, coverage);
	}
	
	
	@Override
	public ScoreModel getCompareScore(String scoreShortname) {
		// needed for protein FDR calculation
		if (ScoreModelEnum.PROTEIN_SCORE.isValidDescriptor(scoreShortname)) {
			return score;
		} else {
			return null;
		}
	}
	
	
	/**
	 * Returns the protein score, if the argument is a valid descriptor for it,
	 * or NaN, if not.
	 */
	@Override
	public Double getScore(String scoreName) {
		if (ScoreModelEnum.PROTEIN_SCORE.isValidDescriptor(scoreName)) {
			return score.getValue();
		} else {
			return Double.NaN;
		}
	}
	
	
	@Override
	public Long getRank() {
		return rank;
	}
	
	
	@Override
	public void setRank(Long rank) {
		this.rank = rank;
	}
	
	
	/**
	 * Returns a List of the subsets of this protein.
	 * @return
	 */
	public List<ReportProtein> getSubSets() {
		return subSetProteins;
	}
	
	
	/**
	 * Adds the given ReportProtein to the subset proteins.
	 * @param subProtein
	 */
	public void addToSubsets(ReportProtein subProtein) {
		subSetProteins.add(subProtein);
	}
	
	
	@Override
	public double getFDR() {
		return fdrValue;
	}
	
	
	@Override
	public void setFDR(double fdr) {
		this.fdrValue = fdr;
	}
	
	
	@Override
	public double getQValue() {
		return qValue;
	}
	
	
	@Override
	public void setQValue(double value) {
		qValue = value;
	}
	
	
	@Override
	public void dumpFDRCalculation() {
		isFDRGood = false;
		fdrValue = Double.POSITIVE_INFINITY;
	}
	
	
	@Override
	public void updateDecoyStatus(DecoyStrategy strategy, Pattern p) {
		switch (strategy) {
		case ACCESSIONPATTERN:
			// go through all accessions, if there is one without decoy pattern, the protein is no decoy
			Matcher m;
			isDecoy = true;
			
			for (Map.Entry<String, Accession> accIt : accMap.entrySet()) {
				m = p.matcher(accIt.getValue().getAccession());
				isDecoy &= m.matches();
				if (!isDecoy) {
					break;
				}
			}
			break;
			
		case SEARCHENGINE:
			// go through all the spectra, if there is one not flagged as decoy, the protein is no decoy
			isDecoy = true;
			
			for (ReportPeptide pep : peptideMap.values()) {
				for (PSMReportItem psmSet : pep.getPSMs()) {
					if (psmSet instanceof ReportPSMSet) {
						for (ReportPSM psm : ((ReportPSMSet) psmSet).getPSMs()) {
							if ((psm.getSpectrum().getIsDecoy() == null) ||
									!psm.getSpectrum().getIsDecoy()) {
								isDecoy = false;
								break;
							}
						}
					} else {
						// TODO: better error, though this cannot happen
						System.err.println("ReportProtein.updateDecoyStatus: " +
								"not a ReportPSMSet in ReportProtein, this " +
								"should not happen!");
					}
					
					if (!isDecoy) {
						break;
					}
				}
			}
			break;
		}
	}
	
	
	/**
	 * Getter for isFDRGood
	 * @return
	 */
	public boolean getIsFDRGood() {
		return isFDRGood;
	}
	
	
	/**
	 * Setter for isFDRGood
	 * @param isGood
	 */
	@Override
	public void setIsFDRGood(boolean isGood) {
		isFDRGood = isGood;
	}
	
	
	@Override
	public boolean getIsDecoy() {
		return isDecoy;
	}
}
