package org.grobid.core.engines;

import org.grobid.core.GrobidModels;
import org.grobid.core.document.Document;
import org.grobid.core.document.DocumentPiece;
import org.grobid.core.document.DocumentPointer;
import org.grobid.core.engines.citations.LabeledReferenceResult;
import org.grobid.core.engines.citations.ReferenceSegmenter;
import org.grobid.core.engines.tagging.GenericTaggerUtils;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.features.FeatureFactory;
import org.grobid.core.features.FeaturesVectorReferenceSegmenter;
import org.grobid.core.layout.Block;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.utilities.BoundingBoxCalculator;
import org.grobid.core.utilities.Pair;
import org.grobid.core.utilities.TextUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.regex.Matcher;

/**
 * @author Slava, Patrice
 * Date: 4/14/14
 */
public class ReferenceSegmenterParser extends AbstractParser implements ReferenceSegmenter{
	private static final Logger LOGGER = LoggerFactory.getLogger(ReferenceSegmenterParser.class);

    // projection scale for line length
    private static final int LINESCALE = 10;

    protected ReferenceSegmenterParser() {
        super(GrobidModels.REFERENCE_SEGMENTER);
    }

    /**
     *
     * @param doc Document object
     * @return <reference_label, reference_string>  Note, that label is null when no label was detected
     *              example: <"[1]", "Hu W., Barkana, R., &amp; Gruzinov A. Phys. Rev. Lett. 85, 1158">
     */
	public List<LabeledReferenceResult> extract(Document doc) {
		return extract(doc, false);
	}

    public List<LabeledReferenceResult> extract(Document doc, boolean training) {
		SortedSet<DocumentPiece> referencesParts = doc.getDocumentPart(SegmentationLabel.REFERENCES);
		Pair<String,List<LayoutToken>> featSeg = getReferencesSectionFeatured(doc, referencesParts);
		String res;
		List<LayoutToken> tokenizationsReferences;
		if (featSeg == null) {
			return null;
		}
		// if featSeg is null, it usually means that no reference segment is found in the
		// document segmentation
		String featureVector = featSeg.getA();
		tokenizationsReferences = featSeg.getB();
		try {
			res = label(featureVector);
		}
		catch(Exception e) {
			throw new GrobidException("CRF labeling in ReferenceSegmenter fails.", e);
		}
		if (res == null) {
			return null;
		}
        List<Pair<String, String>> labeled = GenericTaggerUtils.getTokensAndLabels(res);

		// if we extract for generating training data, we also give back the used features
		if (training) {
			return getExtractionResult(tokenizationsReferences, labeled, featureVector);
		} else {
			return getExtractionResult(tokenizationsReferences, labeled, null);
		}
    }

    private List<LabeledReferenceResult> getExtractionResult(List<LayoutToken> tokenizations, List<Pair<String, String>> labeled, String featureVectors) {
        List<LabeledReferenceResult> resultList = new ArrayList<LabeledReferenceResult>();
        StringBuilder reference = new StringBuilder();
		List<LayoutToken> referenceTokens = new ArrayList<LayoutToken>();
        StringBuilder referenceLabel = new StringBuilder();
		StringBuilder features = new StringBuilder();
		String[] featureLines = null;

		if (featureVectors != null)
			featureLines = featureVectors.split("\n");

        int tokPtr = 0;
		int featureLineIndex = 0;
        boolean addSpace = false;
		boolean addLine = false;
        for (Pair<String, String> l : labeled) {
            String tok = l.a;
            String label = l.b;
			String theFeatures = null;
			if ((featureLines != null) && (featureLines.length > 0)) {
				theFeatures = featureLines[featureLineIndex];
				featureLineIndex++;
				// we need to remove the final label at the end of the feature line
				int ind = theFeatures.lastIndexOf(" ");
				if (ind != -1)
					theFeatures = theFeatures.substring(0, ind);
			}
            while(tokPtr < tokenizations.size()) {
				while (tokenizations.get(tokPtr).t().equals(" ") ||
					   tokenizations.get(tokPtr).t().equals("\n") ||
					   tokenizations.get(tokPtr).t().equals("\r") ) {
					if (tokenizations.get(tokPtr).t().equals(" ")) {
						addSpace = true;
					} else {
						addLine = true;
					}
                	tokPtr++;
				}
				break;
            }

			LayoutToken layoutToken = tokenizations.get(tokPtr);
            if (tokPtr >= tokenizations.size()) {
                //throw new IllegalStateException("Implementation error: Reached the end of tokenizations, but current token is " + tok);
				LOGGER.error("Implementation error: Reached the end of tokenizations, but current token is " + tok);
				// we add a space to avoid concatenated text
				addSpace = true;
            }
            else {
				String tokenizationToken = layoutToken.getText();
				if ((tokPtr != tokenizations.size()) && !tokenizationToken.equals(tok)) {
					// and we add a space by default to avoid concatenated text
					addSpace = true;
					if (!tok.startsWith(tokenizationToken)) {
						// this is a very exceptional case due to a sequence of accent/diacresis, in this case we skip
						// a shift in the tokenizations list and continue on the basis of the labeled token
						// we check one ahead
						tokPtr++;
						tokenizationToken = tokenizations.get(tokPtr).getText();
						if (!tok.equals(tokenizationToken)) {
							// we try another position forward (second hope!)
							tokPtr++;
							tokenizationToken = tokenizations.get(tokPtr).getText();
							if (!tok.equals(tokenizationToken)) {
								// we try another position forward (last hope!)
								tokPtr++;
								tokenizationToken = tokenizations.get(tokPtr).getText();
								if (!tok.equals(tokenizationToken)) {
									// we return to the initial position
									tokPtr = tokPtr-3;
									tokenizationToken = tokenizations.get(tokPtr).getText();
									LOGGER.error("Implementation error, tokens out of sync: " +
										tokenizationToken + " != " + tok + ", at position " + tokPtr);
								}
							}
						}
					}
					// note: if the above condition is true, this is an exceptional case due to a
					// sequence of accent/diacresis and we can go on as a full string match
	            }
			}

            String plainLabel = GenericTaggerUtils.getPlainLabel(label);
            if (plainLabel.equals("<label>")) {
                if (GenericTaggerUtils.isBeginningOfEntity(label)) {
                    if (reference.length() != 0) {
                        resultList.add(new LabeledReferenceResult(referenceLabel.length() == 0 ? null :
                                referenceLabel.toString().trim(), reference.toString().trim(), features.toString(), BoundingBoxCalculator.calculate(referenceTokens)));
                        reference.setLength(0);
                        referenceLabel.setLength(0);
						features.setLength(0);
						referenceTokens.clear();
                    }
                }
                if (addSpace || addLine) {
                    referenceLabel.append(' ');
                    addSpace = false;
                }

                referenceLabel.append(tok);
				features.append(theFeatures);
				features.append("\n");

            } else if (plainLabel.equals("<reference>")) {
                if (GenericTaggerUtils.isBeginningOfEntity(label)) {
                    if (reference.length() != 0) {
                        resultList.add(new LabeledReferenceResult(referenceLabel.length() == 0 ?
                                null : referenceLabel.toString().trim(),
									reference.toString().trim(), features.toString(), BoundingBoxCalculator.calculate(referenceTokens)));
                        reference.setLength(0);
                        referenceLabel.setLength(0);
						features.setLength(0);
						referenceTokens.clear();
                    }
                }
                if (addSpace) {
                    reference.append(' ');
                    addSpace = false;
                }
                if (addLine) {
                    reference.append('\n');
                    addLine = false;
                }

                reference.append(tok);
				referenceTokens.add(layoutToken);
				features.append(theFeatures);
				features.append("\n");
            }
			else if (plainLabel.equals("<other>")) {
				features.append(theFeatures);
				features.append("\n");
			}
            tokPtr++;
        }

        if (reference.length() != 0) {
            resultList.add(new LabeledReferenceResult(referenceLabel.length() == 0 ? null :
                    referenceLabel.toString().trim(), reference.toString().trim(),
					features.toString(),
					BoundingBoxCalculator.calculate(referenceTokens)));
            reference.setLength(0);
            referenceLabel.setLength(0);
        }

//        for (LabeledReferenceResult r : resultList) {
//            System.out.println(r);
//        }
        return resultList;
    }

	public org.grobid.core.utilities.Pair<String,String> createTrainingData(Document doc, int id) {
		SortedSet<DocumentPiece> referencesParts = doc.getDocumentPart(SegmentationLabel.REFERENCES);
		Pair<String,List<LayoutToken>> featSeg = getReferencesSectionFeatured(doc, referencesParts);
		String res;
		List<LayoutToken> tokenizations;
		if (featSeg == null) {
			return null;
		}
		// if featSeg is null, it usually means that no reference segment is found in the
		// document segmentation
		String featureVector = featSeg.getA();
		tokenizations = featSeg.getB();
		try {
			res = label(featureVector);
		}
		catch(Exception e) {
			throw new GrobidException("CRF labeling in ReferenceSegmenter fails.", e);
		}
		if (res == null) {
			return null;
		}
        List<Pair<String, String>> labeled = GenericTaggerUtils.getTokensAndLabels(res);		
        StringBuilder sb = new StringBuilder();

		//noinspection StringConcatenationInsideStringBufferAppend
		sb.append("<tei>\n" +
				"    <teiHeader>\n" +
				"        <fileDesc xml:id=\"_" + id + "\"/>\n" +
				"    </teiHeader>\n" +
				"    <text xml:lang=\"en\">\n" +
				"        <listBibl>\n");
		
		int tokPtr = 0;
		boolean addSpace = false;
		boolean addEOL = false;
		String lastTag = null;
		boolean refOpen = false;
		for (Pair<String, String> l : labeled) {
            String tok = l.a;
            String label = l.b;

			int tokPtr2 = tokPtr;
            for(; tokPtr2 < tokenizations.size(); tokPtr2++) {
                if (tokenizations.get(tokPtr2).t().equals(" ")) {
					addSpace = true;
				}
				else if (tokenizations.get(tokPtr2).t().equals("\n") ||
					     tokenizations.get(tokPtr).t().equals("\r") ) {
					addEOL = true;	
				}
                else {
					break;
				}
            }
			tokPtr = tokPtr2;

            if (tokPtr >= tokenizations.size()) {
				LOGGER.error("Implementation error: Reached the end of tokenizations, but current token is " + tok);
				// we add a space to avoid concatenated text
				addSpace = true;
            }
            else {
				String tokenizationToken = tokenizations.get(tokPtr).getText();

				if ((tokPtr != tokenizations.size()) && !tokenizationToken.equals(tok)) {
					// and we add a space by default to avoid concatenated text
					addSpace = true;
					if (!tok.startsWith(tokenizationToken)) {
						// this is a very exceptional case due to a sequence of accent/diacresis, in this case we skip
						// a shift in the tokenizations list and continue on the basis of the labeled token
						// we check one ahead
						tokPtr++;
						tokenizationToken = tokenizations.get(tokPtr).getText();
						if (!tok.equals(tokenizationToken)) {
							// we try another position forward (second hope!)
							tokPtr++;
							tokenizationToken = tokenizations.get(tokPtr).getText();
							if (!tok.equals(tokenizationToken)) {
								// we try another position forward (last hope!)
								tokPtr++;
								tokenizationToken = tokenizations.get(tokPtr).getText();
								if (!tok.equals(tokenizationToken)) {
									// we return to the initial position
									tokPtr = tokPtr-3;
									tokenizationToken = tokenizations.get(tokPtr).getText();
									LOGGER.error("Implementation error, tokens out of sync: " +
										tokenizationToken + " != " + tok + ", at position " + tokPtr);
								}
							}
						}
					}
					// note: if the above condition is true, this is an exceptional case due to a
					// sequence of accent/diacresis and we can go on as a full string match
	            }
			}	
			
			String plainLabel = GenericTaggerUtils.getPlainLabel(label);
			
			boolean tagClosed = (lastTag != null) && testClosingTag(sb, label, lastTag, addSpace, addEOL);
			
			if (tagClosed) {
				addSpace = false;
				addEOL = false;
			}
			if (tagClosed && lastTag.equals("<reference>")) {
				refOpen = false;
			}
			String output;
			String field;
			if (refOpen) {
				field = "<label>";
			}
			else {
				field = "<bibl><label>";
			}
			output = writeField(label, lastTag, tok, "<label>", field, addSpace, addEOL, 2);
			if (output != null) {
				sb.append(output);
				refOpen = true;
			}
			else {
				if (refOpen) {
					field = "";
				}
				else {
					field = "<bibl>";
				}
				output = writeField(label, lastTag, tok, "<reference>", field, addSpace, addEOL, 2);
				if (output != null) {
					sb.append(output);
					refOpen= true;
				}
				else {
					output = writeField(label, lastTag, tok, "<other>", "", addSpace, addEOL, 2);
					if (output != null) {
						sb.append(output);
						refOpen = false;
					}
				}
			}
			
			lastTag = plainLabel;
			addSpace = false;
			addEOL = false;
            tokPtr++;
        }

		if (refOpen) {
			sb.append("</bibl>");
		}

        sb.append("\n        </listBibl>\n" +
                "    </text>\n" +
                "</tei>\n");
		
		return new Pair<String, String>(sb.toString(), featureVector);
    }


	private boolean testClosingTag(StringBuilder buffer,
                                   String currentTag,
                                   String lastTag,
								   boolean addSpace,
								   boolean addEOL) {
        boolean res = false;
        if (!currentTag.equals(lastTag)) {
            res = true;
            // we close the current tag
            if (lastTag.equals("<other>")) {
				if (addEOL)
                    buffer.append("<lb/>");
				if (addSpace)
                    buffer.append(" ");
                buffer.append("\n");
            } else if (lastTag.equals("<label>")) {
				buffer.append("</label>");
				if (addEOL)
                    buffer.append("<lb/>");
				if (addSpace)
                    buffer.append(" ");
            } else if (lastTag.equals("<reference>")) {
				if (addEOL)
                    buffer.append("<lb/>");
				if (addSpace)
                    buffer.append(" ");
                buffer.append("</bibl>\n");
            } else {
                res = false;
            }
        }
        return res;
    }

    private String writeField(String currentTag,
                              String lastTag,
                              String token,
                              String field,
                              String outField,
                              boolean addSpace,
							  boolean addEOL,
							  int nbIndent) {
        String result = null;
        if (currentTag.endsWith(field)) {
            if (currentTag.endsWith("<other>")) {
                result = "";
				if (currentTag.equals("I-<other>")) {
					result += "\n";
					for (int i = 0; i < nbIndent; i++) {
	                    result += "    ";
	                }
				}
				if (addEOL)
                    result += "<lb/>";
				if (addSpace)
                    result += " ";
                result += TextUtilities.HTMLEncode(token);
            }
			else if ((lastTag != null) && currentTag.endsWith(lastTag)) {
                result = "";
				if (addEOL)
                    result += "<lb/>";
				if (addSpace)
                    result += " ";
				if (currentTag.startsWith("I-"))
					result += outField;
                result += TextUtilities.HTMLEncode(token);
            }
			else {
                result = "";
				if (outField.length() > 0) {
					for (int i = 0; i < nbIndent; i++) {
                    	result += "    ";
                	}
				}
				if (addEOL)
                    result += "<lb/>";
                if (addSpace)
                    result += " ";
                result += outField + TextUtilities.HTMLEncode(token);
            }
        }
        return result;
    }

	static public Pair<String,List<LayoutToken>> getReferencesSectionFeatured(Document doc,
												SortedSet<DocumentPiece> referencesParts) {
		if ((referencesParts == null) || (referencesParts.size() == 0)) {
			return null;
		}
		FeatureFactory featureFactory = FeatureFactory.getInstance();
		List<Block> blocks = doc.getBlocks();
		if ( (blocks == null) || blocks.size() == 0) {
			return null;
		}

		StringBuilder citations = new StringBuilder();
        boolean newline;
//        String currentFont = null;
//        int currentFontSize = -1;
        int n; // overall token number

		//int currentJournalPositions = 0;
        //int currentAbbrevJournalPositions = 0;
        //int currentConferencePositions = 0;
        //int currentPublisherPositions = 0;
        //boolean isJournalToken;
        //boolean isAbbrevJournalToken;
        //boolean isConferenceToken;
        //boolean isPublisherToken;
        //boolean skipTest;

		FeaturesVectorReferenceSegmenter features;
		FeaturesVectorReferenceSegmenter previousFeatures = null;
		boolean endblock;
		boolean startblock;
        //int mm = 0; // token position in the sentence
        int nn; // token position in the line
		double lineStartX = Double.NaN;
		boolean indented = false;

		List<LayoutToken> tokenizationsReferences = new ArrayList<LayoutToken>();
		List<LayoutToken> tokenizations = doc.getTokenizations();

		int maxLineLength = 1;
		//List<Integer> lineLengths = new ArrayList<Integer>();
		int currentLineLength = 0;
		//int lineIndex = 0;

        // we calculate current max line length and intialize the body tokenization structure
		for(DocumentPiece docPiece : referencesParts) {
			DocumentPointer dp1 = docPiece.a;
			DocumentPointer dp2 = docPiece.b;

            int tokens = dp1.getTokenDocPos();
            int tokene = dp2.getTokenDocPos();
            for (int i = tokens; i <= tokene; i++) {
                tokenizationsReferences.add(tokenizations.get(i));
				currentLineLength += tokenizations.get(i).getText().length();
				if (tokenizations.get(i).t().equals("\n") || tokenizations.get(i).t().equals("\r") ) {
					//lineLengths.add(currentLineLength);
					if (currentLineLength > maxLineLength)
						maxLineLength = currentLineLength;
					currentLineLength = 0;
				}
            }
		}

		for(DocumentPiece docPiece : referencesParts) {
			DocumentPointer dp1 = docPiece.a;
			DocumentPointer dp2 = docPiece.b;
			
/*for(int i=dp1.getTokenDocPos(); i<dp2.getTokenDocPos(); i++) {
	System.out.print(tokenizations.get(i));
}	
System.out.println("");
*/
			//currentLineLength = lineLengths.get(lineIndex);
			nn = 0;
			int tokenIndex = 0;
			int blockIndex = dp1.getBlockPtr();
			Block block = null;
			List<LayoutToken> tokens;
			boolean previousNewline = true;
			currentLineLength = 0;
			String currentLineProfile = null;
			for(n = dp1.getTokenDocPos(); n < dp2.getTokenDocPos(); n++) {
				String text = tokenizations.get(n).getText();

				if (text == null) {
					continue;
				}

				// set corresponding block
				if ( (block != null) && (n > block.getEndToken()) ) {
					blockIndex++;
					tokenIndex = 0;
					currentLineLength = 0;
					currentLineProfile = null;
				}

				if (blockIndex<blocks.size()) {
					block = blocks.get(blockIndex);
					if (n == block.getStartToken()) {
						startblock = true;
						endblock = false;
					}
					else if (n == block.getEndToken()) {
						startblock = false;
						endblock = true;
					}
					else {
						startblock = false;
						endblock = false;
					}
				}
				else {
					block = null;
					startblock = false;
					endblock = false;
				}
				// set corresponding token
	            if (block != null)
					tokens = block.getTokens();
				else
					tokens = null;

				if (text.equals("\n") || text.equals("\r")) {
					previousNewline = true;
                    nn = 0;
					currentLineLength = 0;
					currentLineProfile = null;
					//lineIndex++;
					//currentLineLength = lineLengths.get(lineIndex);
                    continue;
                }
				else {
                    newline = false;
					nn += text.length(); // +1 for segmentation symbol
				}

				if (text.equals(" ") || text.equals("\t")) {
                    nn++;
                    continue;
				}

				if (text.trim().length() == 0) {
					continue;
				}

                LayoutToken token = null;
                if (tokens != null) {
                    int i = tokenIndex;
                    while (i < tokens.size()) {
                        token = tokens.get(i);
                        if (text.equals(token.getText())) {
                            tokenIndex = i;
                            break;
                        }
                        i++;
                    }
                }

                if (previousNewline) {
                    newline = true;
                    previousNewline = false;
					if (token != null && previousFeatures != null) {
						double previousLineStartX = lineStartX;
                        lineStartX = token.getX();
                        double characterWidth = token.width / token.getText().length();
						if (!Double.isNaN(previousLineStartX)) {
                            // Indentation if line start is > 1 character width to the right of previous line start
                            if (lineStartX - previousLineStartX > characterWidth)
        					    indented = true;
        					// Indentation ends if line start is > 1 character width to the left of previous line start
                            else if (previousLineStartX - lineStartX > characterWidth)
                                indented = false;
                            // Otherwise indentation is unchanged
						}
					}
                }

				if (TextUtilities.filterLine(text)) {
                    continue;
                }

                features = new FeaturesVectorReferenceSegmenter();
                features.token = token;
                features.string = text;

                if (newline) {
                    features.lineStatus = "LINESTART";
                }
                Matcher m0 = featureFactory.isPunct.matcher(text);
                if (m0.find()) {
                    features.punctType = "PUNCT";
                }
                if (text.equals("(") || text.equals("[")) {
                    features.punctType = "OPENBRACKET";

                } else if (text.equals(")") || text.equals("]")) {
                    features.punctType = "ENDBRACKET";

                } else if (text.equals(".")) {
                    features.punctType = "DOT";

                } else if (text.equals(",")) {
                    features.punctType = "COMMA";

                } else if (text.equals("-")) {
                    features.punctType = "HYPHEN";

                } else if (text.equals("\"") || text.equals("\'") || text.equals("`")) {
                    features.punctType = "QUOTE";

                }

                if ( (n == 0) || (previousNewline) ) {
                    features.lineStatus = "LINESTART";
					if (n == 0)
						features.blockStatus = "BLOCKSTART";
					nn = 0;
                }

                if (indented) {
                	features.alignmentStatus = "LINEINDENT";
                }
                else {
                	features.alignmentStatus = "ALIGNEDLEFT";
                }

				{
                    // look ahead...
                    boolean endline = true;

                    int ii = 1;
                    boolean endloop = false;
					String accumulated = text;
                    while ((n + ii < tokenizations.size()) && (!endloop)) {
                        String tok = tokenizations.get(n + ii).getText();
                        if (tok != null) {
							if (currentLineProfile == null)
								accumulated += tok;
                            if (tok.equals("\n") || tok.equals("\r")) {
                                endloop = true;
								if (currentLineLength ==0) {
									currentLineLength = accumulated.length();
								}
								if (currentLineProfile == null) {
									currentLineProfile = TextUtilities.punctuationProfile(accumulated);
								}
                            }
							else if (!tok.equals(" ") && !tok.equals("\t")) {
								endline = false;
							}
							else {
                                if (TextUtilities.filterLine(tok)) {
                                    endloop = true;
									if (currentLineLength ==0) {
										currentLineLength = accumulated.length();
									}
									if (currentLineProfile == null) {
										currentLineProfile = TextUtilities.punctuationProfile(accumulated);
									}
                                }
                            }
                        }

                        if (n + ii >= tokenizations.size() - 1) {
                            endblock = true;
                            endline = true;
                        }

						if (endline && (block != null) && (n+ii == block.getEndToken())) {
							endblock = true;
						}
                        ii++;
                    }

                    if ((!endline) && !(newline)) {
                        features.lineStatus = "LINEIN";
                    }
					else if (!newline) {
                        features.lineStatus = "LINEEND";
                        previousNewline = true;
                    }

					if (startblock) {
						features.blockStatus = "BLOCKSTART";
					}
                    if ((!endblock) && (features.blockStatus == null))
                        features.blockStatus = "BLOCKIN";
                    else if (features.blockStatus == null) {
                        features.blockStatus = "BLOCKEND";
                    }
                }

                if (text.length() == 1) {
                    features.singleChar = true;
                }

                if (Character.isUpperCase(text.charAt(0))) {
                    features.capitalisation = "INITCAP";
                }

                if (featureFactory.test_all_capital(text)) {
                    features.capitalisation = "ALLCAP";
                }

                if (featureFactory.test_digit(text)) {
                    features.digit = "CONTAINSDIGITS";
                }

                if (featureFactory.test_common(text)) {
                    features.commonName = true;
                }

                if (featureFactory.test_names(text)) {
                    features.properName = true;
                }

                if (featureFactory.test_month(text)) {
                    features.month = true;
                }

                Matcher m = featureFactory.isDigit.matcher(text);
                if (m.find()) {
                    features.digit = "ALLDIGIT";
                }

                Matcher m2 = featureFactory.YEAR.matcher(text);
                if (m2.find()) {
                    features.year = true;
                }

                Matcher m3 = featureFactory.EMAIL.matcher(text);
                if (m3.find()) {
                    features.email = true;
                }

                Matcher m4 = featureFactory.HTTP.matcher(text);
                if (m4.find()) {
                    features.http = true;
                }

                if ( (token != null) && (token.getBold()) )
                    features.bold = true;

                if ( (token != null) && (token.getItalic()) )
                    features.italic = true;

                if (features.capitalisation == null)
                    features.capitalisation = "NOCAPS";

                if (features.digit == null)
                    features.digit = "NODIGIT";

                if (features.punctType == null)
                    features.punctType = "NOPUNCT";
//System.out.println(nn + "\t" + currentLineLength + "\t" + maxLineLength);
                features.lineLength = featureFactory
                        .linearScaling(currentLineLength, maxLineLength, LINESCALE);

				features.relativePosition = featureFactory
                         .linearScaling(nn, currentLineLength, LINESCALE);

				features.punctuationProfile = currentLineProfile;

                if (previousFeatures != null)
                    citations.append(previousFeatures.printVector());
                //mm++;
                previousFeatures = features;
			}
		}
		if (previousFeatures != null)
	      	citations.append(previousFeatures.printVector());

	   	return new Pair<String,List<LayoutToken>>(citations.toString(), tokenizationsReferences);
	}
}
