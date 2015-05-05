package edu.stanford.nlp.naturalli.entail;

import edu.stanford.nlp.classify.GeneralDataset;
import edu.stanford.nlp.classify.RVFDataset;
import edu.stanford.nlp.entail.BleuMeasurer;
import edu.stanford.nlp.ie.machinereading.structure.Span;
import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.kbp.common.CollectionUtils;
import edu.stanford.nlp.ling.RVFDatum;
import edu.stanford.nlp.simple.Sentence;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.util.Execution;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.Trilean;
import edu.stanford.nlp.util.logging.Redwood;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static edu.stanford.nlp.util.logging.Redwood.Util.log;

/**
 * A featurizer, which takes two sentences (or rather, a sentence pair) as input, and outputs a list of features.
 *
 * @author Gabor Angeli
 */
public class EntailmentFeaturizer implements Serializable {
  private static final long serialVersionUID = 42L;

  @Execution.Option(name="features", gloss="The feature templates to use during training")
  public Set<FeatureTemplate> FEATURE_TEMPLATES = new HashSet<FeatureTemplate>(){{
//    add(FeatureTemplate.BLEU);
//    add(FeatureTemplate.LENGTH_DIFF);
//    add(FeatureTemplate.OVERLAP);
//    add(FeatureTemplate.POS_OVERLAP);
    add(FeatureTemplate.KEYWORD_OVERLAP);
//    add(FeatureTemplate.KEYWORD_OVERLAP_KERNEL);

//    add(FeatureTemplate.ENTAIL_UNIGRAM);
//    add(FeatureTemplate.ENTAIL_BIGRAM);
    add(FeatureTemplate.ENTAIL_KEYWORD);
//    add(FeatureTemplate.CONCLUSION_NGRAM);
  }};
  @Execution.Option(name="features.nolex", gloss="If true, prohibit all lexical features")
  public boolean FEATURES_NOLEX = false;

  public EntailmentFeaturizer(String[] args) {
    Execution.fillOptions(this, args);
  }

  public EntailmentFeaturizer(Properties props) {
    Execution.fillOptions(this, props);
  }

  static Set<KeywordPair> align(EntailmentPair ex, Optional<DebugDocument> debugDocument, boolean manyToMany) {
    // Get the spans
    List<Span> premiseSpans = ex.premise.algorithms().keyphraseSpans();
    List<Span> conclusionSpans = ex.conclusion.algorithms().keyphraseSpans();
    // Run algorithm
    return align(ex, premiseSpans, conclusionSpans, debugDocument, manyToMany);
  }

  /**
   * Heuristically align the keywords in the premise to the keywords in the conclusion.
   * @param ex The premise/hypothesis pair to align.
   * @param debugDocument A debug document to dump the alignments to.
   * @param manyToMany If true, allow many-to-many alignments.
   * @return An set of keyword pairs representing the alignment.
   */
  static Set<KeywordPair> align(EntailmentPair ex, List<Span> premiseSpans, List<Span> conclusionSpans, Optional<DebugDocument> debugDocument, boolean manyToMany) {

    // Get other useful metadata
    List<String> premisePhrases = premiseSpans.stream().map(x -> StringUtils.join(ex.premise.lemmas().subList(x.start(), x.end()), " ").toLowerCase()).collect(Collectors.toList());
    boolean[] premiseConsumed = new boolean[premiseSpans.size()];
    List<String> conclusionPhrases = conclusionSpans.stream().map(x -> StringUtils.join(ex.conclusion.lemmas().subList(x.start(), x.end()), " ").toLowerCase()).collect(Collectors.toList());
    boolean[] conclusionConsumed = new boolean[conclusionSpans.size()];
    int[] conclusionForPremise = new int[premiseSpans.size()];
    Arrays.fill(conclusionForPremise, -1);

    // The return set
    Set<KeywordPair> alignments = new HashSet<>();

    // Pass 1: exact match
    for (int pI = 0; pI < premiseSpans.size(); ++pI) {
      if (premiseConsumed[pI]) { continue; }
      for (int cI = 0; cI < conclusionSpans.size(); ++cI) {
        if (conclusionConsumed[cI]) { continue; }
        if (premisePhrases.get(pI).equals(conclusionPhrases.get(cI))) {
          alignments.add(new KeywordPair(ex.premise, premiseSpans.get(pI), ex.conclusion, conclusionSpans.get(cI)));
          premiseConsumed[pI] = true;
          conclusionConsumed[cI] = true;
          conclusionForPremise[pI] = cI;
          break;
        }
      }
    }

    // Pass 2: approximate match (ends with)
    for (int pI = 0; pI < premiseSpans.size(); ++pI) {
      if (premiseConsumed[pI]) { continue; }
      for (int cI = 0; cI < conclusionSpans.size(); ++cI) {
        if (conclusionConsumed[cI]) { continue; }
        if (premisePhrases.get(pI).endsWith(conclusionPhrases.get(cI)) || conclusionPhrases.get(cI).endsWith(premisePhrases.get(pI))) {
          alignments.add(new KeywordPair(ex.premise, premiseSpans.get(pI), ex.conclusion, conclusionSpans.get(cI)));
          premiseConsumed[pI] = true;
          conclusionConsumed[cI] = true;
          conclusionForPremise[pI] = cI;
          break;
        }
      }
    }

    // Pass 3: approximate match (starts with)
    for (int pI = 0; pI < premiseSpans.size(); ++pI) {
      if (premiseConsumed[pI]) { continue; }
      for (int cI = 0; cI < conclusionSpans.size(); ++cI) {
        if (conclusionConsumed[cI]) { continue; }
        if (premisePhrases.get(pI).startsWith(conclusionPhrases.get(cI)) || conclusionPhrases.get(cI).startsWith(premisePhrases.get(pI))) {
          alignments.add(new KeywordPair(ex.premise, premiseSpans.get(pI), ex.conclusion, conclusionSpans.get(cI)));
          premiseConsumed[pI] = true;
          conclusionConsumed[cI] = true;
          conclusionForPremise[pI] = cI;
          break;
        }
      }
    }

    // Pass 4: approximate word match (shared word prefix / suffix)
    for (int pI = 0; pI < premiseSpans.size(); ++pI) {
      if (premiseConsumed[pI]) { continue; }
      String[] premiseWords = premisePhrases.get(pI).toLowerCase().split("\\s+");
      for (int cI = 0; cI < conclusionSpans.size(); ++cI) {
        if (conclusionConsumed[cI]) { continue; }
        String[] conclusionWords = conclusionPhrases.get(cI).toLowerCase().split("\\s+");
        if (premiseWords[0].equalsIgnoreCase(conclusionWords[0]) ||
            premiseWords[premiseWords.length - 1].equalsIgnoreCase(conclusionWords[conclusionWords.length - 1])) {
          alignments.add(new KeywordPair(ex.premise, premiseSpans.get(pI), ex.conclusion, conclusionSpans.get(cI)));
          premiseConsumed[pI] = true;
          conclusionConsumed[cI] = true;
          conclusionForPremise[pI] = cI;
          break;
        }
      }
    }

    // Pass 5: approximate word match (any word containment)
    for (int pI = 0; pI < premiseSpans.size(); ++pI) {
      if (premiseConsumed[pI]) { continue; }
      Set<String> premiseWords = new HashSet<>(Arrays.asList(premisePhrases.get(pI).split("\\s+")));
      for (int cI = 0; cI < conclusionSpans.size(); ++cI) {
        if (conclusionConsumed[cI]) { continue; }
        Set<String> commonWords = new HashSet<>(Arrays.asList(conclusionPhrases.get(cI).split("\\s+")));
        commonWords.retainAll(premiseWords);
        if (!commonWords.isEmpty()) {
          alignments.add(new KeywordPair(ex.premise, premiseSpans.get(pI), ex.conclusion, conclusionSpans.get(cI)));
          premiseConsumed[pI] = true;
          conclusionConsumed[cI] = true;
          conclusionForPremise[pI] = cI;
          break;
        }
      }
    }

    // Pass 6: many-to-many alignment pass
    if (manyToMany) {
      for (int pI = 0; pI < premiseSpans.size(); ++pI) {
        for (int cI = 0; cI < conclusionSpans.size(); ++cI) {
          if ((!premiseConsumed[pI] || !conclusionConsumed[cI]) &&
              premisePhrases.get(pI).equalsIgnoreCase(conclusionPhrases.get(cI))) {
            alignments.add(new KeywordPair(ex.premise, premiseSpans.get(pI), ex.conclusion, conclusionSpans.get(cI)));
            premiseConsumed[pI] = true;
            conclusionConsumed[cI] = true;
            conclusionForPremise[pI] = cI;
            break;
          }
        }
      }
    }

    // Last pass: constrained POS match
    //noinspection StatementWithEmptyBody
    if (premiseConsumed.length == 0) {
      // noop
    } else if (premiseConsumed.length == 1) {
      // Case: one token keywords
      if (conclusionSpans.size() == 1 && conclusionForPremise[0] == -1 &&
          ex.premise.algorithms().modeInSpan(premiseSpans.get(0), Sentence::posTags).charAt(0) == ex.conclusion.algorithms().modeInSpan(conclusionSpans.get(0), Sentence::posTags).charAt(0)) {
        // ...and the POS tags match, and there's only one consequent. They must align.
        alignments.add(new KeywordPair(ex.premise, premiseSpans.get(0), ex.conclusion, conclusionSpans.get(0)));
        premiseConsumed[0] = true;
        conclusionConsumed[0] = true;
        conclusionForPremise[0] = 0;
      }
    } else {
      // Case: general alignment
      for (int i = 0; i < conclusionForPremise.length; ++i) {
        if (conclusionForPremise[i] < 0) {
          // find a candidate alignment for token 'i'
          int candidateAlignment = -1;
          if (i == 0 && conclusionForPremise[i + 1] == 1) {
            candidateAlignment = conclusionForPremise[i + 1] - 1;
          } else if (i == conclusionForPremise.length - 1 && conclusionForPremise[i - 1] >= 0 && conclusionForPremise[i - 1] == conclusionSpans.size() - 2) {
            candidateAlignment = conclusionForPremise[i - 1] + 1;
          } else if (i > 0 && i < conclusionForPremise.length - 1 &&
              conclusionForPremise[i - 1] >= 0 && conclusionForPremise[i + 1] >= 0 &&
              conclusionForPremise[i + 1] - conclusionForPremise[i - 1] == 2) {
            candidateAlignment = conclusionForPremise[i - 1] + 1;
          }
          // sanity check that alignment (e.g., with POS tags)
          if (candidateAlignment >= 0 &&
              ex.premise.algorithms().modeInSpan(premiseSpans.get(i), Sentence::posTags).charAt(0) == ex.conclusion.algorithms().modeInSpan(conclusionSpans.get(candidateAlignment), Sentence::posTags).charAt(0)) {
            // Ensure we're not double-aligning.
            if (manyToMany || (!premiseConsumed[i] && !conclusionConsumed[candidateAlignment])) {
              // Add the alignment
              alignments.add(new KeywordPair(ex.premise, premiseSpans.get(i), ex.conclusion, conclusionSpans.get(candidateAlignment)));
              premiseConsumed[i] = true;
              conclusionConsumed[candidateAlignment] = true;
              conclusionForPremise[i] = candidateAlignment;
            }
          }
        }
      }
    }

    // Finally: unaligned keywords
    for (int i = 0; i < premiseSpans.size(); ++i) {
      if (!premiseConsumed[i]) {
        alignments.add(new KeywordPair(ex.premise, premiseSpans.get(i), ex.conclusion, null));
        premiseConsumed[i] = true;
      }
    }
    for (int i = 0; i < conclusionSpans.size(); ++i) {
      if (!conclusionConsumed[i]) {
        alignments.add(new KeywordPair(ex.premise, null, ex.conclusion, conclusionSpans.get(i)));
        conclusionConsumed[i] = true;
      }
    }

    // Return
    if (debugDocument.isPresent()) {
      debugDocument.get().registerAlignment(ex, alignments);
    }
    return alignments;
  }

  /**
   * Compute the BLEU score between two sentences.
   *
   * @param n The BLEU-X value to compute. Default is BLEU-4.
   * @param premiseWord The words in the premise.
   * @param conclusionWord The words in the conclusion.
   *
   * @return The BLEU score between the premise and the conclusion.
   */
  private static double bleu(int n, List<String> premiseWord, List<String> conclusionWord) {
    BleuMeasurer bleuScorer = new BleuMeasurer(n);
    bleuScorer.addSentence(premiseWord.toArray(new String[premiseWord.size()]), conclusionWord.toArray(new String[conclusionWord.size()]));
    double bleu = bleuScorer.bleu();
    if (Double.isNaN(bleu)) {
      bleu = 0.0;
    }
    return bleu;
  }

  static boolean containmentOverlap(String a, String b) {
    return a.toLowerCase().startsWith(b.toLowerCase()) || b.toLowerCase().startsWith(a.toLowerCase());
  }

  static boolean wordContainmentOverlap(String a, String b) {
    Set<String> setA = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\s+")));
    Set<String> setB = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\s+")));
    setB.retainAll(setA);
    return !setB.isEmpty();
  }

  /**
   * Featurize a given entailment pair.
   *
   * @param ex The example to featurize.
   *
   * @return A Counter containing the real-valued features for this example.
   */
  public Counter<String> featurize(EntailmentPair ex, Optional<DebugDocument> debugDocument) {
    ClassicCounter<String> feats = new ClassicCounter<>();
    feats.incrementCount("bias");
    List<Span> premiseKeywords = ex.premise.algorithms().keyphraseSpans();
    List<Span> conclusionKeywords = ex.conclusion.algorithms().keyphraseSpans();
    List<String> premiseKeyphrases = premiseKeywords.stream().map(x -> StringUtils.join(ex.premise.lemmas().subList(x.start(), x.end()), " ").toLowerCase()).collect(Collectors.toList());
    List<String> conclusionKeyphrases = conclusionKeywords.stream().map(x -> StringUtils.join(ex.conclusion.lemmas().subList(x.start(), x.end()), " ").toLowerCase()).collect(Collectors.toList());

    // Lemma overlap
    if (FEATURE_TEMPLATES.contains(FeatureTemplate.OVERLAP)) {
      int intersect = CollectionUtils.intersect(new HashSet<>(ex.premise.lemmas()), new HashSet<>(ex.conclusion.lemmas())).size();
      feats.incrementCount("lemma_overlap", intersect);
      feats.incrementCount("lemma_overlap_percent", ((double) intersect) / ((double) Math.min(ex.premise.length(), ex.conclusion.length())));
    }

    // Relevant POS intersection
    if (FEATURE_TEMPLATES.contains(FeatureTemplate.POS_OVERLAP)) {
      for (char pos : new HashSet<Character>() {{
        add('V');
        add('N');
        add('J');
        add('R');
      }}) {
        Set<String> premiseVerbs = new HashSet<>();
        for (int i = 0; i < ex.premise.length(); ++i) {
          if (ex.premise.posTag(i).charAt(0) == pos) {
            premiseVerbs.add(ex.premise.lemma(i));
          }
        }
        Set<String> conclusionVerbs = new HashSet<>();
        for (int i = 0; i < ex.conclusion.length(); ++i) {
          if (ex.conclusion.posTag(i).charAt(0) == pos) {
            premiseVerbs.add(ex.conclusion.lemma(i));
          }
        }
        Set<String> intersectVerbs = CollectionUtils.intersect(premiseVerbs, conclusionVerbs);
        feats.incrementCount("" + pos + "_overlap_percent", ((double) intersectVerbs.size() / ((double) Math.min(ex.premise.length(), ex.conclusion.length()))));
        feats.incrementCount("" + pos + "_overlap: " + intersectVerbs.size());
      }
    }

    if (FEATURE_TEMPLATES.contains(FeatureTemplate.KEYWORD_OVERLAP)) {
      Set<KeywordPair> alignments = align(ex, premiseKeywords, conclusionKeywords, debugDocument, false);
      long onlyInPremise = alignments.stream().filter(x -> x.hasPremise() && !x.hasConclusion()).count();
      long onlyInHypothesis = alignments.stream().filter(x -> x.hasConclusion() && !x.hasPremise()).count();
      long onlyContainmentMatch = alignments.stream().filter(EntailmentFeaturizer.KeywordPair::isAligned).filter(x -> !x.premiseLemma().equalsIgnoreCase(x.conclusionLemma()) && containmentOverlap(x.premiseLemma(), x.conclusionLemma())).count();
      long onlyWordContainmentMatch = alignments.stream().filter(EntailmentFeaturizer.KeywordPair::isAligned).filter(x -> !x.premiseLemma().equalsIgnoreCase(x.conclusionLemma()) && !containmentOverlap(x.premiseLemma(), x.conclusionLemma()) && wordContainmentOverlap(x.premiseLemma(), x.conclusionLemma())).count();
      long notOverlap = alignments.stream().filter(EntailmentFeaturizer.KeywordPair::isAligned).filter(x -> !x.premiseLemma().equalsIgnoreCase(x.conclusionLemma()) && !containmentOverlap(x.premiseLemma(), x.conclusionLemma()) && !wordContainmentOverlap(x.premiseLemma(), x.conclusionLemma())).count();
      long anyOverlap = alignments.stream().filter(EntailmentFeaturizer.KeywordPair::isAligned).count();
      long perfectMatch = alignments.stream().filter(EntailmentFeaturizer.KeywordPair::isAligned).filter(x -> x.premiseLemma().equalsIgnoreCase(x.conclusionLemma())).count();

      double onlyInPremisePenalty = premiseKeyphrases.isEmpty() ? 0.0 : ((double) onlyInPremise) / ((double) premiseKeyphrases.size());
      double onlyInHypothesisPenalty = conclusionKeyphrases.isEmpty() ? 0.0 : ((double) onlyInHypothesis) / ((double) conclusionKeyphrases.size());
      double onlyContainmentPenalty = (double) onlyContainmentMatch;
      double onlyWordContainmentPenalty = (double) onlyWordContainmentMatch;
      double noOverlapPenaltyPremise = notOverlap == 0 ? 0.0 : (double) notOverlap / ((double) premiseKeyphrases.size());
      double noOverlapPenaltyConclusion = notOverlap == 0 ? 0.0 : (double) notOverlap / ((double) conclusionKeyphrases.size());
      double noOverlapPenaltyPercent = alignments.isEmpty() ? 0.0 : (double) notOverlap / ((double) alignments.size());
      double anyOverlapBonus = alignments.size() == 0 ? 0.0 : ((double) anyOverlap) / ((double) alignments.size());
      double perfectMatchBonusPremise = perfectMatch == 0 ? 0.0 : ((double) perfectMatch) / ((double) premiseKeyphrases.size());
      double perfectMatchBonusConclusion = perfectMatch == 0 ? 0.0 : ((double) perfectMatch) / ((double) conclusionKeyphrases.size());
      double perfectMatchBonusPercent = alignments.isEmpty() ? 0.0 :  ((double) perfectMatch) / ((double) alignments.size());

      feats.incrementCount("onlyInPremise", onlyInPremisePenalty);
      feats.incrementCount("onlyInConclusion", onlyInHypothesisPenalty);
//      feats.incrementCount("onlyContainment", onlyContainmentPenalty);
//      feats.incrementCount("onlyWordContainment", onlyWordContainmentPenalty);
      feats.incrementCount("noOverlapPremise", noOverlapPenaltyPremise);
      feats.incrementCount("noOverlapConclusion", noOverlapPenaltyConclusion);
      feats.incrementCount("noOverlapPercent", noOverlapPenaltyPercent);
      feats.incrementCount("noOverlapCount", notOverlap);
      feats.incrementCount("anyOverlap", anyOverlapBonus);
//      feats.incrementCount("anyOverlapCount", anyOverlap);
      feats.incrementCount("perfectMatchPremise", perfectMatchBonusPremise);
      feats.incrementCount("perfectMatchConclusion", perfectMatchBonusConclusion);
      feats.incrementCount("perfectMatchPercent", perfectMatchBonusPercent);
      feats.incrementCount("perfectMatchCount", perfectMatch);
//      feats.incrementCount("perfectMatchCount", perfectMatch);

      if (FEATURE_TEMPLATES.contains(FeatureTemplate.KEYWORD_OVERLAP_KERNEL)) {
        List<Pair<String, Double>> stats = new ArrayList<Pair<String, Double>>(){{
          add(Pair.makePair("onlyInPremise", onlyInPremisePenalty));
          add(Pair.makePair("onlyInConclusion", onlyInHypothesisPenalty));
//          add(Pair.makePair("onlyContainment", onlyContainmentPenalty));
//          add(Pair.makePair("onlyWordContainment", onlyWordContainmentPenalty));
          add(Pair.makePair("noOverlap", noOverlapPenaltyPercent));
          add(Pair.makePair("anyOverlap", anyOverlapBonus));
          add(Pair.makePair("perfectMatch", perfectMatchBonusPercent));
//          add(Pair.makePair("anyOverlapCount", (double) anyOverlap));
//          add(Pair.makePair("perfectMatchCount", (double) perfectMatch));
        }};
        for (int i = 0; i < stats.size(); ++i) {
          for (int k = 0; k < stats.size(); ++k) {
            if (i != k) {
//              feats.incrementCount("multiplicative_kernel(" + stats.get(i).first + "," + stats.get(k).first + ")", stats.get(i).second * stats.get(k).second);
//              feats.incrementCount("additive_kernel(" + stats.get(i).first + "," + stats.get(k).first + ")", stats.get(i).second + stats.get(k).second);
              feats.incrementCount("nonzero_kernel(" + stats.get(i).first + "," + stats.get(k).first + ")",
                  (stats.get(i).second != 0.0 &&  stats.get(i).second != 0.0) ? 1.0 : 0.0);
              feats.incrementCount("zero_kernel(" + stats.get(k).first + "," + stats.get(k).first + ")",
                  (stats.get(i).second == 0.0 &&  stats.get(k).second == 0.0) ? 1.0 : 0.0);
            }
          }
        }

      }
    }

    // BLEU score
    if (FEATURE_TEMPLATES.contains(FeatureTemplate.BLEU)) {
      feats.incrementCount("BLEU-1", bleu(1, ex.premise.lemmas(), ex.premise.lemmas()));
      feats.incrementCount("BLEU-2", bleu(2, ex.premise.lemmas(), ex.premise.lemmas()));
      feats.incrementCount("BLEU-3", bleu(3, ex.premise.lemmas(), ex.premise.lemmas()));
      feats.incrementCount("BLEU-4", bleu(4, ex.premise.lemmas(), ex.premise.lemmas()));
    }

    // Length differences
    if (FEATURE_TEMPLATES.contains(FeatureTemplate.LENGTH_DIFF)) {
      feats.incrementCount("length_diff:" + (ex.conclusion.length() - ex.premise.length()));
      feats.incrementCount("conclusion_longer?:" + (ex.conclusion.length() > ex.premise.length()));
      feats.incrementCount("conclusion_length:" + ex.conclusion.length());
    }

    // Unigram entailments
    if (!FEATURES_NOLEX && FEATURE_TEMPLATES.contains(FeatureTemplate.ENTAIL_UNIGRAM)) {
      for (int pI = 0; pI < ex.premise.length(); ++pI) {
        for (int cI = 0; cI < ex.conclusion.length(); ++cI) {
          if (ex.premise.posTag(pI).charAt(0) == ex.conclusion.posTag(cI).charAt(0)) {
            feats.incrementCount("lemma_entail:" + ex.premise.lemma(pI) + "_->_" + ex.conclusion.lemma(cI));
          }
        }
      }
    }

    // Bigram entailments
    if (!FEATURES_NOLEX && FEATURE_TEMPLATES.contains(FeatureTemplate.ENTAIL_BIGRAM)) {
      for (int pI = 0; pI <= ex.premise.length(); ++pI) {
        String lastPremise = (pI == 0 ? "^" : ex.premise.lemma(pI - 1));
        String premise = (pI == ex.premise.length() ? "$" : ex.premise.lemma(pI));
        for (int cI = 0; cI <= ex.conclusion.length(); ++cI) {
          String lastConclusion = (cI == 0 ? "^" : ex.conclusion.lemma(cI - 1));
          String conclusion = (cI == ex.conclusion.length() ? "$" : ex.conclusion.lemma(cI));
          if ((pI == ex.premise.length() && cI == ex.conclusion.length()) ||
              (pI < ex.premise.length() && cI < ex.conclusion.length() &&
                  ex.premise.posTag(pI).charAt(0) == ex.conclusion.posTag(cI).charAt(0))) {
            feats.incrementCount("lemma_entail:" + lastPremise + "_" + premise + "_->_" + lastConclusion + "_" + conclusion);
          }
        }
      }
    }

    // Keyword entailments
    if (!FEATURES_NOLEX && FEATURE_TEMPLATES.contains(FeatureTemplate.ENTAIL_KEYWORD)) {
      for (Span p : premiseKeywords) {
        String premisePOS = ex.premise.algorithms().modeInSpan(p, Sentence::posTags);
        String premisePhrase = StringUtils.join(ex.premise.lemmas().subList(p.start(), p.end()), " ").toLowerCase();
        for (Span c : conclusionKeywords) {
          String conclusionPOS = ex.conclusion.algorithms().modeInSpan(c, Sentence::posTags);
          if (premisePOS.charAt(0) == conclusionPOS.charAt(0)) {
            String conclusionPhrase = StringUtils.join(ex.conclusion.lemmas().subList(c.start(), c.end()), " ").toLowerCase();
            feats.incrementCount("keyphrase_entail:" + premisePhrase + "_->_" + conclusionPhrase);
          }
        }
      }
    }

    // Consequent uni/bi-gram
    if (!FEATURES_NOLEX && FEATURE_TEMPLATES.contains(FeatureTemplate.CONCLUSION_NGRAM)) {
      for (int i = 0; i < ex.conclusion.length(); ++i) {
        String elem = "^_";
        if (i > 0) {
          elem = ex.conclusion.lemma(i - 1) + "_";
        }
        elem += ex.conclusion.lemma(i);
        feats.incrementCount("conclusion_unigram:" + ex.conclusion.lemma(i));
        feats.incrementCount("conclusion_bigram:" + elem);
      }
    }

    assert Counters.isFinite(feats);

    return feats;
  }

  /**
   * Featurize an entire dataset, caching the intermediate annotations in the process.
   *
   * @param data The dataset to featurize, as a (potentially lazy, necessarily parallel) stream.
   * @param cacheStream The cache stream to write the processed sentences to.
   *
   * @return A dataset with the featurized data.
   *
   * @throws java.io.IOException Thrown from the underlying write method to the cache.
   */
  public GeneralDataset<Trilean, String> featurize(Stream<EntailmentPair> data, OutputStream cacheStream, Optional<DebugDocument> debugDocument, boolean parallel) throws IOException {
    GeneralDataset<Trilean, String> dataset = new RVFDataset<>();
    final AtomicInteger i = new AtomicInteger(0);
    long startTime = System.currentTimeMillis();
    (parallel ? data.parallel() : data).forEach(ex -> {
      if (ex != null) {
        Counter<String> featurized = featurize(ex, debugDocument);
        if (i.incrementAndGet() % 1000 == 0) {
          log("[" + Redwood.formatTimeDifference(System.currentTimeMillis() - startTime) + "] featurized " + (i.get() / 1000) + "k examples");
        }
        synchronized (ClassifierTrainer.class) {
          dataset.add(new RVFDatum<>(featurized, ex.truth));
          if (cacheStream != null) {
            ex.serialize(cacheStream);
          }
        }
      }
    });
    if (cacheStream != null) {
      cacheStream.close();
    }
    return dataset;
  }


  /** @see edu.stanford.nlp.naturalli.entail.EntailmentFeaturizer#featurize(Stream, OutputStream, Optional, boolean) */
  public GeneralDataset<Trilean, String> featurize(Stream<EntailmentPair> data) {
    try {
      return featurize(data, null, Optional.empty(), false);
    } catch (IOException e) {
      throw new RuntimeIOException("(should be impossible!!!)", e);
    }
  }

  enum FeatureTemplate {
    BLEU, LENGTH_DIFF,
    OVERLAP, POS_OVERLAP, KEYWORD_OVERLAP, KEYWORD_OVERLAP_KERNEL,
    ENTAIL_UNIGRAM, ENTAIL_BIGRAM, ENTAIL_KEYWORD,
    CONCLUSION_NGRAM,
  }

  /**
   * A pair of aligned keywords that exist in either one of, or both the premise and the hypothesis.
   */
  static class KeywordPair {
    public final Sentence premise;
    public final Span premiseSpan;
    public final Sentence conclusion;
    public final Span conclusionSpan;

    public KeywordPair(Sentence premise, Span premiseSpan, Sentence conclusion, Span conclusionSpan) {
      this.premise = premise;
      this.premiseSpan = premiseSpan == null ? new Span(-1, -1) : premiseSpan;
      this.conclusion = conclusion;
      this.conclusionSpan = conclusionSpan == null ? new Span(-1, -1) : conclusionSpan;
    }

    public String premiseChunk() {
      return StringUtils.join(premise.words().subList(premiseSpan.start(), premiseSpan.end()), " ");
    }

    public String conclusionChunk() {
      return StringUtils.join(conclusion.words().subList(conclusionSpan.start(), conclusionSpan.end()), " ");
    }

    public String premiseLemma() {
      return StringUtils.join(premise.lemmas().subList(premiseSpan.start(), premiseSpan.end()), " ");
    }

    public String conclusionLemma() {
      return StringUtils.join(conclusion.lemmas().subList(conclusionSpan.start(), conclusionSpan.end()), " ");
    }

    public boolean isAligned() {
      return premiseSpan.size() > 0 && conclusionSpan.size() > 0;
    }

    public boolean hasPremise() {
      return premiseSpan.size() > 0;
    }

    public boolean hasConclusion() {
      return conclusionSpan.size() > 0;
    }

    @Override
    public String toString() {
      return "< " + (premiseSpan.size() > 0 ? premiseChunk() : "--- ") + "; " + (conclusionSpan.size() > 0 ? conclusionChunk() : "---") + " >";
    }
  }
}