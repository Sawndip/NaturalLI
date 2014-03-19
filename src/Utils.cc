#include "Utils.h"

using namespace std;

const vector<word> lemursHaveTails() {
  vector<word> fact;
  fact.push_back(LEMUR);
  fact.push_back(HAVE);
  fact.push_back(TAIL);
  return fact;
}

const vector<word> animalsHaveTails() {
  vector<word> animalsHaveTails;
  animalsHaveTails.push_back(ANIMAL);
  animalsHaveTails.push_back(HAVE);
  animalsHaveTails.push_back(TAIL);
  return animalsHaveTails;
}

const vector<word> catsHaveTails() {
  vector<word> catsHaveTails;
  catsHaveTails.push_back(CAT);
  catsHaveTails.push_back(HAVE);
  catsHaveTails.push_back(TAIL);
  return catsHaveTails;
}

string toString(const Graph& graph, const tagged_word* fact, const uint8_t factLength) {
  string gloss = "";
  for (int i = 0; i < factLength; ++i) {
    monotonicity m = getMonotonicity(fact[i]);
    std::string marker = "";
    if (m == MONOTONE_DOWN) { marker = "[v]"; }
    if (m == MONOTONE_UP) { marker = "[^]"; }
    gloss = gloss + (gloss == "" ? "" : " ") + marker + graph.gloss(getWord(fact[i]));
  }
  return gloss;
}

string toString(const Graph& graph, SearchType& searchType, const Path* path) {
  if (path == NULL) {
    return "<start>";
  } else {
    return toString(graph, path->fact, path->factLength) +
           "; from\n  " +
           toString(graph, searchType, path->parent);
  }
}

std::string toString(const edge_type& edge) {
  switch (edge) {
    case WORDNET_UP                   : return "WORDNET_UP";
    case WORDNET_DOWN                 : return "WORDNET_DOWN";
    case WORDNET_NOUN_ANTONYM         : return "WORDNET_NOUN_ANTONYM";
    case WORDNET_VERB_ANTONYM         : return "WORDNET_VERB_ANTONYM";
    case WORDNET_ADJECTIVE_ANTONYM    : return "WORDNET_ADJECTIVE_ANTONYM";
    case WORDNET_ADVERB_ANTONYM       : return "WORDNET_ADVERB_ANTONYM";
    case WORDNET_ADJECTIVE_PERTAINYM  : return "WORDNET_ADJECTIVE_PERTAINYM";
    case WORDNET_ADVERB_PERTAINYM     : return "WORDNET_ADVERB_PERTAINYM";
    case WORDNET_ADJECTIVE_RELATED    : return "WORDNET_ADJECTIVE_RELATED";
    case ANGLE_NN                     : return "ANGLE_NN";
    case FREEBASE_UP                  : return "FREEBASE_UP";
    case FREEBASE_DOWN                : return "FREEBASE_DOWN";
    case MORPH_TO_LEMMA               : return "MORPH_TO_LEMMA";
    case MORPH_FROM_LEMMA             : return "MORPH_FROM_LEMMA";
    case MORPH_FUDGE_NUMBER           : return "MORPH_FUDGE_NUMBER";
    case SENSE_REMOVE                 : return "SENSE_REMOVE";
    case SENSE_ADD                    : return "SENSE_ADD";
    case ADD_NOUN                     : return "ADD_NOUN";
    case ADD_VERB                     : return "ADD_VERB";
    case ADD_ADJ                      : return "ADD_ADJ";
    case ADD_ADV                      : return "ADD_ADV";
    case DEL_NOUN                     : return "DEL_NOUN";
    case DEL_VERB                     : return "DEL_VERB";
    case DEL_ADJ                      : return "DEL_ADJ";
    case DEL_ADV                      : return "DEL_ADV";
    default: return "UNK_EDGE_TYPE";
  }
}
