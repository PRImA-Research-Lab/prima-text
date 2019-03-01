package org.primaresearch.text.eval.character;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.primaresearch.eval.EvaluationResult;
import org.primaresearch.shared.variable.DoubleValue;
import org.primaresearch.shared.variable.DoubleVariable;
import org.primaresearch.shared.variable.IntegerValue;
import org.primaresearch.shared.variable.IntegerVariable;
import org.primaresearch.shared.variable.StringValue;
import org.primaresearch.shared.variable.StringVariable;
import org.primaresearch.shared.variable.Variable.WrongVariableTypeException;
import org.primaresearch.shared.variable.VariableMap;
import org.primaresearch.text.comp.EditDistance;
import org.primaresearch.text.comp.EditDistance.CostFunction;
import org.primaresearch.text.eval.HasEvaluationOptions;
import org.primaresearch.text.eval.TextEvaluator;
import org.primaresearch.text.eval.character.CharacterAccuracy.CharacterAccuracyResult;

/**
 * Accuracy measure based on edit distance. Reduces impact of reading order of text blocks.
 * The flex character accuracy is always greater or equal to the traditional character accuracy.
 * 
 * Default cost function is (ins,del,subst) = (1,1,1)
 * 
 * @author clc
 *
 */
public class FlexCharacterAccuracy implements TextEvaluator, HasEvaluationOptions {

	private static final String OPT_CostFunction = "CostFunction";
	
	private CostFunction costFunction = CostFunction.INS1_DEL1_SUBST1;
	
	private VariableMap options;
	
	private Map<String, Map<String,MatchResult>> matchCache = null;
	
	private static final boolean localDebug = false;
	
	/** 
	 * Constructor 
	 */
	public FlexCharacterAccuracy() {
		//Evaluation options
		options = new VariableMap();
		options.setName("FlexCharacterAccuracyOptions");
		
		StringVariable costFunc = new StringVariable(OPT_CostFunction);
		options.add(costFunc);
		try {
			costFunc.setValue(new StringValue(costFunction.getKey()));
		} catch (WrongVariableTypeException e) {
		}
	}	
	
	@Override
	public EvaluationResult evaluate(String expected, String result) {
		FlexCharacterAccuracyResult evalResult = new FlexCharacterAccuracyResult();
		matchCache = new HashMap<String, Map<String,MatchResult>>();
				
		double maxCharAcc = 0.0;
		
		//Calculate conventional character accuracy first (as minimum)
		CharacterAccuracy charAcc = new CharacterAccuracy();
		CharacterAccuracyResult charAccRes;
		charAcc.setCostFunction(costFunction);
		charAccRes = (CharacterAccuracyResult)charAcc.evaluate(expected.replaceAll("\r\n",  "").replaceAll("\n", ""), 
																result.replaceAll("\r\n",  "").replaceAll("\n", ""));
		maxCharAcc = ((DoubleValue)charAccRes.getValues().get(CharacterAccuracyResult.V_CharacterAccuracy).getValue()).val;
		
		//Flex character accuracy
		int expectedNumberOfChars = 0;
		int resultNumberOfChars = 0;
		
		//Try different coeffs and choose highest character accuracy
		int editDistCoeff; 		//25
		int lenghtDiffCoeff;	//20
		int offsetCoeff;		//1
		int lengthCoeff;		//4
		
		for (editDistCoeff = 15; editDistCoeff <= 30; editDistCoeff += 5) {
			for (lenghtDiffCoeff = 0; lenghtDiffCoeff <= 23; lenghtDiffCoeff += 3) {
				for (offsetCoeff = 0; offsetCoeff <= 3; offsetCoeff += 1) {
					for (lengthCoeff = 0; lengthCoeff <= 5; lengthCoeff += 1) {
					
						//Split into lines
						List<String> expectedLines = splitIntoLines(expected);
						List<String> resultLines = splitIntoLines(result);

						double characterAccuracy = 1.0;
						if (expected.length() > 0 || result.length() > 0) {
							int[] values = calculateFlexEditDistance(expectedLines, resultLines, editDistCoeff, lenghtDiffCoeff, offsetCoeff, lengthCoeff);
							
							expectedNumberOfChars = values[0];
							resultNumberOfChars = values[1];
							int editDist = values[2]; 
						
							characterAccuracy = 0.0;
							if (expectedNumberOfChars > 0) {
								characterAccuracy = (double)(expectedNumberOfChars - editDist) / (double)expectedNumberOfChars;
								if (characterAccuracy < 0.0)
									characterAccuracy = 0.0;
							}
							if (characterAccuracy > maxCharAcc)
								maxCharAcc = characterAccuracy;
						}
					}
				}
			}
		}		
		
		//Store results
		DoubleVariable charAccVar = (DoubleVariable)evalResult.getValues().get(FlexCharacterAccuracyResult.V_CharacterAccuracy);
		try {
			charAccVar.setValue(new DoubleValue(maxCharAcc));
		} catch (WrongVariableTypeException e) {
		}
		
		//Counts
		IntegerVariable charsGT = (IntegerVariable)evalResult.getValues().get(FlexCharacterAccuracyResult.V_NumberOfCharactersInGroundTruth);
		try {
			charsGT.setValue(new IntegerValue(expectedNumberOfChars));
		} catch (WrongVariableTypeException e) {
		}

		IntegerVariable charsRes = (IntegerVariable)evalResult.getValues().get(FlexCharacterAccuracyResult.V_NumberOfCharactersInResult);
		try {
			charsRes.setValue(new IntegerValue(resultNumberOfChars));
		} catch (WrongVariableTypeException e) {
		}

		return evalResult;
	}
	
	/**
	 * Calculates the flex edit distance between two given sets of text lines
	 * 
	 * @param expectedLines Ground truth
	 * @param resultLines OCR result or similar
	 * @param editDistCoeff
	 * @param lenghtDiffCoeff
	 * @param offsetCoeff
	 * @param lengthCoeff
	 * @return Array of [expected number of chars, result number of chars, total edit distance]
	 */
	private int[] calculateFlexEditDistance(List<String> expectedLines, List<String> resultLines, 
											final int editDistCoeff, final int lenghtDiffCoeff, final int offsetCoeff, final int lengthCoeff) {
		
		int totalEditDist = 0;	
		int expectedNumberOfChars = 0;
		int resultNumberOfChars = 0;
		
		//Count characters
		for (String str : expectedLines) 
			expectedNumberOfChars += str.length();
		
		for (String str : resultLines) 
			resultNumberOfChars += str.length();		
		
		sortByLength(expectedLines);
	
		//For all expected lines
		String expectedLine;
		String resultLine;
		String left = null, right = null;
		
		while (!expectedLines.isEmpty()) {
			
			//Find best match
			int minEditDist = Integer.MAX_VALUE;
			int minPenalty = Integer.MAX_VALUE;
			int minEditDistResultIndex = -1;
			int minEditDistExpectedIndex = -1;
			int minEditDistSubstringPos = 0;

			//For all expected lines
			for (int i=0; i<expectedLines.size(); i++) {
				expectedLine = expectedLines.get(i);
				
				// For all result lines
				for (int j=0; j<resultLines.size(); j++) {
					resultLine = resultLines.get(j);
					
					MatchResult res = calculateBestEditDistance(expectedLine, resultLine);  
					
					int penalty = res.calcPenalty(editDistCoeff, lenghtDiffCoeff, offsetCoeff, lengthCoeff);
					if (penalty < minPenalty) {
						minPenalty = penalty;
						minEditDist = res.minEditDist;
						minEditDistExpectedIndex = i;
						minEditDistResultIndex = j;
						minEditDistSubstringPos = res.substringPos;
					}
				}
			}
				
			//Split into substrings?
			if (minEditDistResultIndex >= 0) {
				expectedLine = expectedLines.get(minEditDistExpectedIndex); 
				resultLine = resultLines.get(minEditDistResultIndex);
				if (expectedLine.length() > resultLine.length()) {
					//Split
					left = minEditDistSubstringPos > 0 ? expectedLine.substring(0, minEditDistSubstringPos-1) : null;
					right = minEditDistSubstringPos + resultLine.length() < expectedLine.length() ? expectedLine.substring(minEditDistSubstringPos + resultLine.length()) : null;
				
					//Append left and right remaining snippet to end of list
					if (left != null && !left.trim().isEmpty())
						expectedLines.add(left.trim());
					if (right != null && !right.trim().isEmpty())
						expectedLines.add(right.trim());
				}
				else if (resultLine.length() > expectedLine.length()) {
					//Split
					left = minEditDistSubstringPos > 0 ? resultLine.substring(0, minEditDistSubstringPos-1) : null;
					right = minEditDistSubstringPos + expectedLine.length() < resultLine.length() ? resultLine.substring(minEditDistSubstringPos + expectedLine.length()) : null;
				
					//Append left and right remaining snippet to end of list
					if (left != null && !left.trim().isEmpty())
						resultLines.add(left.trim());
					if (right != null && !right.trim().isEmpty())
						resultLines.add(right.trim());
				}
				
				//Remove current result line
				resultLines.remove(minEditDistResultIndex);
				
				if (localDebug) 
					System.out.println("Expected: '"+expectedLine + "',  result: '"+resultLine + "',  editDist: "+minEditDist);
				
				if (minEditDist != Integer.MAX_VALUE)
					totalEditDist += minEditDist;
				else
					totalEditDist += expectedLine.length();
				
				//Remove current expected line
				expectedLines.remove(minEditDistExpectedIndex);
				if (localDebug) 
					System.out.println("Finished expected: '"+expectedLine + "'");
			}
			else
				break;
			
			//Re-sort
			sortByLength(expectedLines);
		}
		
		//Now handle all result lines that were not matched with expected lines
		int deletionPenality = 0;
		if (costFunction.getKey().equals(CostFunction.INS1_DEL1_SUBST1.getKey()))
			deletionPenality = 1;
		else if (costFunction.getKey().equals(CostFunction.INS1_DEL1_SUBST2.getKey()))
			deletionPenality = 1;
		
		if (deletionPenality > 0) {
			for (String line : resultLines) {
				//Treat as deletions
				totalEditDist += line.length() * deletionPenality;
				if (localDebug) 
					System.out.println("Result line not matched: '"+line + "'");
			}
		}		
		
		//Handle all expected lines that were not matched with result lines
		int insertPenality = 1;
		
		for (String line : expectedLines) {
			//Treat as deletions
			totalEditDist += line.length() * insertPenality;
			if (localDebug) 
				System.out.println("Expected line not matched: '"+line + "'");
		}
		
		if (localDebug) 
			System.out.println("Total edit dist: "+totalEditDist);
		
		return new int[] {expectedNumberOfChars, resultNumberOfChars, totalEditDist};
	}
	
	/**
	 * Sorts by string length (long to short)
	 * @param strings
	 */
	private void sortByLength(List<String> strings) {
		Collections.sort(strings, new Comparator<String>() {

			@Override
			public int compare(String o1, String o2) {
				return new Integer(o2.length()).compareTo(new Integer(o1.length()));
			}
		});
	}
	
	/**
	 * Calculates all edit distances of the shorter string within the longer string
	 * @param expected Ground truth
	 * @param result OCR result or similar
	 * @return 
	 */
	private MatchResult calculateBestEditDistance(String expected, String result) {
		
		//Check cache first
		Map<String, MatchResult> resultEntries = matchCache.get(expected);
		if (resultEntries != null) {
			MatchResult item = resultEntries.get(result);
			if (item != null)
				return item;
		}
		else {
			//Create map
			resultEntries = new HashMap<String, MatchResult>();
			matchCache.put(expected, resultEntries);
		}
		
		MatchResult res = new MatchResult();
		
		int minEditDist = Integer.MAX_VALUE;
		int minPos = -1;

		if (expected.length() > result.length()) {
			res.lengthDiff = expected.length() - result.length();
			for (int i=0; i<=res.lengthDiff; i++) {
				String subString = expected.substring(i, i+result.length()-1);
				
				int editDist = EditDistance.calculateEditDistance(result, subString, costFunction);
				if (editDist < minEditDist) {
					minEditDist = editDist;
					minPos = i;
				}
			}
			res.minEditDist = minEditDist;
			res.substringPos = minPos;
			res.substringLength = result.length();
		}
		else if (result.length() > expected.length()){
			res.lengthDiff = result.length() - expected.length();
			for (int i=0; i<=res.lengthDiff; i++) {
				String subString = result.substring(i, i+expected.length()-1);
				
				int editDist = EditDistance.calculateEditDistance(subString, expected, costFunction);
				if (editDist < minEditDist) {
					minEditDist = editDist;
					minPos = i;
				}
			}
			res.minEditDist = minEditDist;
			res.substringPos = minPos;
			res.substringLength = expected.length();
		}
		else {
			//Equal length
			res.minEditDist = EditDistance.calculateEditDistance(result, expected, costFunction);
			res.substringLength = result.length();
		}

		//Calculate penalty
		resultEntries.put(result, res);
		return res;
	}

	/**
	 * Splits the given text into lines (detects whether to use \r\n or \n)
	 * @param text
	 * @return
	 */
	List<String> splitIntoLines(String text) {
		
		String[] splitRes = null;
		if (text.contains("\r\n"))
			splitRes = text.split("\r\n");
		else
			splitRes = text.split("\n");
		
		List<String> res = new LinkedList<String>();
		for (int j=0; j<splitRes.length; j++) {
			if (splitRes[j] != null && !splitRes[j].isEmpty())
				res.add(splitRes[j]);				
		}
		
		return res;
	}

	@Override
	public VariableMap getOptions() {
		return options;
	}
	
	/**
	 * Changes the cost functions for the edit distance calculation.
	 * @param costFunction See the CostFunction class for supported functions.
	 */
	public void setCostFunction(CostFunction costFunction) {
		this.costFunction = costFunction;
		
		//Update options
		StringVariable costFunc = (StringVariable)options.get(OPT_CostFunction);
		try {
			costFunc.setValue(new StringValue(costFunction.getKey()));
		} catch (WrongVariableTypeException e) {
		}
	}	
	

	/**
	 * Class holding the evaluation results for flex character accuracy.
	 *   
	 * @author clc
	 *
	 */
	public static class FlexCharacterAccuracyResult implements EvaluationResult {

		public static final String RESULT_NAME = "CharacterAccuracyResult";

		public static final String V_NumberOfCharactersInGroundTruth 	= "charsInGroundTruth";
		public static final String V_NumberOfCharactersInResult 		= "charsInResult";
		public static final String V_CharacterAccuracy 					= "flexCharacterAccuracy";

		VariableMap values;
		
		public FlexCharacterAccuracyResult() {
			values = new VariableMap();
			values.setName(RESULT_NAME);
			
			values.add(new IntegerVariable(V_NumberOfCharactersInGroundTruth, new IntegerValue(0)));
			values.add(new IntegerVariable(V_NumberOfCharactersInResult, new IntegerValue(0)));
			values.add(new DoubleVariable(V_CharacterAccuracy, new DoubleValue(0.0)));
		}
		
		@Override
		public VariableMap getValues() {
			return values;
		}

		@Override
		public VariableMap getValues(int index) {
			return getValues();
		}

		@Override
		public int getResultSetCount() {
			return 1;
		}

		@Override
		public String getCaption() {
			return "Flex Character Accuracy";
		}		
		
		@Override
		public String toString() {
			return  " " + getCaption() + " ";
		}
	}
	
	
	/**
	 * Match result for a substring
	 * 
	 * @author clc
	 *
	 */
	private static final class MatchResult {
		public int minEditDist = 0;
		public int substringPos = 0;
		public int substringLength = 0;
		public int lengthDiff = 0;
		
		public int calcPenalty(final int editDistCoeff, final int lenghtDiffCoeff, final int offsetCoeff, final int lengthCoeff) {
			
			//Offset of substring from left or right side
			int offset = lengthDiff <= 1 ? 0 : lengthDiff / 2 - Math.abs(substringPos - lengthDiff / 2); //The higher the further away the match is from the left or right
			
			return minEditDist * editDistCoeff 
					+ lengthDiff * lenghtDiffCoeff 
					+ offset * offsetCoeff 
					- substringLength * lengthCoeff;
		}
	}

}
