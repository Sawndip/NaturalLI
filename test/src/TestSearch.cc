#include <limits.h>
#include <cfloat>

#include "gtest/gtest.h"
#include "Config.h"
#include "Utils.h"
#include "Search.h"
#include "Graph.h"
#include "FactDB.h"

using namespace std;

//
// Path
//
class PathTest : public ::testing::Test {
 protected:
  virtual void SetUp() {
    searchType = new BreadthFirstSearch();
    lemursHaveTails_ = lemursHaveTails();
    animalsHaveTails_ = animalsHaveTails();
    catsHaveTails_ = catsHaveTails();
    // Add base path element
    root = new Path(&lemursHaveTails_[0], lemursHaveTails_.size());
    // Register paths with search
    searchType->start(root);
    EXPECT_EQ(*root, *searchType->root);
    EXPECT_EQ(*root, *searchType->peek());
    searchType->push(root, 0, 1, 3701, 0, 0, 0.0f);
    dist1 = ((BreadthFirstSearch*) searchType)->debugGet(0);
    EXPECT_EQ(*root, *searchType->root);
    EXPECT_TRUE(searchType->root->parent == NULL);
    searchType->push(dist1, 0, 1, 27970, 0, 1, 0.0f);
    EXPECT_EQ(*root, *searchType->root);
    EXPECT_TRUE(searchType->root->parent == NULL);
    EXPECT_TRUE(searchType->peek()->parent == NULL);
    // Refresh pointers
    dist1 = ((BreadthFirstSearch*) searchType)->debugGet(0);
    dist2 = ((BreadthFirstSearch*) searchType)->debugGet(1);
  }

  virtual void TearDown() {
    delete root, dist1, dist2, searchType;
  }

  const Path* root;
  const Path* dist1;
  const Path* dist2;
  SearchType* searchType;

  std::vector<word> lemursHaveTails_;
  std::vector<word> animalsHaveTails_;
  std::vector<word> catsHaveTails_;
};


// Ensures that a Path element can be created
TEST_F(PathTest, CanCreate) {
  EXPECT_FALSE(root == NULL);
  EXPECT_FALSE(dist1 == NULL);
  EXPECT_FALSE(dist2 == NULL);
}

// Test path equality
TEST_F(PathTest, HasEquality) {
  EXPECT_EQ(*root, *root);
  EXPECT_EQ(*dist1, *dist1);
  EXPECT_EQ(*dist2, *dist2);
}

// Test copy constructor
TEST_F(PathTest, CanCopy) {
  Path newPath(*root);
  EXPECT_EQ(*root, newPath);
  Path newDist1(*dist1);
  EXPECT_EQ(*dist1, newDist1);
  Path newDist2(*dist2);
  EXPECT_EQ(*dist2, newDist2);
}

// Test getting the source path from a path
TEST_F(PathTest, Parent) {
  // Basic null checks
  EXPECT_TRUE(root->parent == NULL);
  EXPECT_FALSE(dist1->parent == NULL);
  EXPECT_FALSE(dist2->parent == NULL);

  // Pointer equality
  EXPECT_EQ(root,  dist1->parent);
  EXPECT_EQ(dist1, dist2->parent);
  
  // Fine-grained equality check
  EXPECT_EQ(root->parent, dist1->parent->parent);
  EXPECT_EQ(root->edgeType, dist1->parent->edgeType);
  ASSERT_EQ(root->factLength, dist1->parent->factLength);
  for (int i = 0; i < root->factLength; ++i) {
    EXPECT_EQ(root->fact[i], dist1->parent->fact[i]);
  }

  // Comprehensive equality check
  EXPECT_EQ(*root, *dist1->parent);
  EXPECT_EQ(*dist1, *dist2->parent);
}


//
// Common Search Type Tests
//

#define InitSearchTypeTests(ClassName, TestName) \
class TestName : public ::testing::Test { \
 protected: \
  virtual void SetUp() { \
    lemursHaveTails_ = lemursHaveTails(); \
    animalsHaveTails_ = animalsHaveTails(); \
    catsHaveTails_ = catsHaveTails(); \
    root = new Path(&lemursHaveTails_[0], lemursHaveTails_.size()); \
    cache = new CacheStrategyNone(); \
    graph = ReadMockGraph(); \
    facts = ReadMockFactDB(); \
    EXPECT_TRUE(search.isEmpty()); \
  } \
\
  virtual void TearDown() { \
    delete cache, graph, facts; \
  } \
\
  ClassName search; \
  const Path* root; \
  CacheStrategy* cache; \
  Graph* graph; \
  FactDB* facts; \
\
  std::vector<word> lemursHaveTails_; \
  std::vector<word> animalsHaveTails_; \
  std::vector<word> catsHaveTails_; \
}; \
TEST_F(TestName, IsEmpty) { \
  EXPECT_TRUE(search.isEmpty()); \
} \
\
TEST_F(TestName, PushPop) { \
  EXPECT_TRUE(search.isEmpty()); \
  search.start(root); \
  EXPECT_FALSE(search.isEmpty()); \
  search.push(root, 0, 1, 3701, 0, 0, 0.0f); \
  EXPECT_FALSE(search.isEmpty()); \
  EXPECT_EQ((Path*) NULL, search.popWithoutScore()->parent); \
  EXPECT_FALSE(search.isEmpty()); \
  const Path* path = search.popWithoutScore(); \
  ASSERT_FALSE(path->parent == NULL); \
  EXPECT_EQ(*root, *path->parent); \
  EXPECT_TRUE(search.isEmpty()); \
} \
\
TEST_F(TestName, RunToySearch) { \
  vector<const Path*> result = Search(graph, facts, \
                                &lemursHaveTails_[0], lemursHaveTails_.size(), \
                                &search, cache, 100); \
  ASSERT_EQ(1, result.size()); \
  ASSERT_EQ(3, result[0]->factLength); \
  EXPECT_EQ(27970, result[0]->fact[0]); \
  EXPECT_EQ(3844,  result[0]->fact[1]); \
  EXPECT_EQ(14221, result[0]->fact[2]); \
  EXPECT_EQ(3701, result[0]->parent->fact[0]); \
  EXPECT_EQ(3844,  result[0]->parent->fact[1]); \
  EXPECT_EQ(14221, result[0]->parent->fact[2]); \
  EXPECT_EQ(2479928, result[0]->parent->parent->fact[0]); \
  EXPECT_EQ(3844,    result[0]->parent->parent->fact[1]); \
  EXPECT_EQ(14221,   result[0]->parent->parent->fact[2]); \
  EXPECT_TRUE(result[0]->parent->parent->parent == NULL); \
}

//
// Breadth First Search (Memory)
//
InitSearchTypeTests(BreadthFirstSearch, BreadthFirstSearchTest)

// Make sure breadth first search really is a queue
TEST_F(BreadthFirstSearchTest, FIFOOrdering) {
  EXPECT_TRUE(search.isEmpty());
  search.start(root);
  EXPECT_FALSE(search.isEmpty());
  search.push(root, 0, 1, 3701,  0, 0, 0.0f);
  search.push(root, 0, 1, 27970, 0, 1, 0.0f);
  const Path* dist1 = search.debugGet(0);
  const Path* dist2 = search.debugGet(1);
  ASSERT_FALSE(search.isEmpty());
  EXPECT_EQ(*root,  *search.popWithoutScore());
  ASSERT_FALSE(search.isEmpty());
  EXPECT_EQ(*dist1, *search.popWithoutScore());
  ASSERT_FALSE(search.isEmpty());
  EXPECT_EQ(*dist2, *search.popWithoutScore());
  EXPECT_TRUE(search.isEmpty());
}

TEST_F(BreadthFirstSearchTest, StressTestAllocation) {
  // Initialize Search
  EXPECT_TRUE(search.isEmpty());
  search.start(root);
  EXPECT_FALSE(search.isEmpty());
  // populate search
  for (int i = 0; i < 120; ++i) {
    search.push(root, 0, 1, i, 0, 0, 0.0f);
    EXPECT_EQ(3, search.debugGet(i)->factLength);
  }
  for (int parent = 0; parent < 100; ++parent) {
    EXPECT_EQ(3, search.debugGet(parent)->factLength);
    for (int i = 0; i < 128; ++i) {
      const Path* p = search.debugGet(parent);
      EXPECT_EQ(3, search.debugGet(parent)->factLength);
      search.push(p, 0, 1, 1000 * parent + i, 0, 0, 0.0f);
    }
  }
  // pop off elements
  EXPECT_FALSE(search.popWithoutScore() == NULL);  // popWithoutScore the root
  for (int i = 0; i < 120 + 100 * 128; ++i) {
    ASSERT_FALSE(search.isEmpty());
    const Path* elem = search.popWithoutScore();
    EXPECT_FALSE(elem == NULL);  // we didn't popWithoutScore off a null element
    EXPECT_EQ(3, elem->factLength);  // we only did mutations
    EXPECT_FALSE(elem->fact == NULL);  // the element's fact is not null
    EXPECT_FALSE(elem->parent == NULL);  // the element has a parent
    for (int k = 0; k < elem->factLength; ++k) {
      EXPECT_GE(elem->fact[k], 0);  // the word is positive (should always be the case anyways)
      EXPECT_LT(elem->fact[k], 120000);  // the word is within bounds (hopefully catches memory corruption errors)
    }
    EXPECT_FALSE(search.debugGet(i)->fact == NULL);  // the internal memory state is not corrupted
  }
  // Final checks
  EXPECT_TRUE(search.isEmpty());
}

//
// Uniform Cost Search
//
InitSearchTypeTests(UniformCostSearch, UniformCostSearchTest)

TEST_F(UniformCostSearchTest, StressTestAllocationAndOrdering) {
  // Initialize Search
  EXPECT_TRUE(search.isEmpty());
  search.start(root);
  EXPECT_FALSE(search.isEmpty());
  // populate search
  for (int i = 0; i < 120; ++i) {
    search.push(root, 0, 1, i, 0, 0, static_cast <float> (rand()) / (static_cast <float> (RAND_MAX/100.0)) );
    EXPECT_EQ(3, search.debugGet(i)->factLength);
  }
  for (int parent = 0; parent < 100; ++parent) {
    EXPECT_EQ(3, search.debugGet(parent)->factLength);
    for (int i = 0; i < 128; ++i) {
      const Path* p = search.debugGet(parent);
      EXPECT_EQ(3, search.debugGet(parent)->factLength);
      search.push(p, 0, 1, 1000 * parent + i, 0, 0, static_cast <float> (rand()) / (static_cast <float> (RAND_MAX/100.0)) );
    }
  }
  // pop off elements
  EXPECT_FALSE(search.popWithoutScore() == NULL);  // popWithoutScore the root
  float lastReturned = 0.0f;
  for (int i = 0; i < 120 + 100 * 128; ++i) {
    ASSERT_FALSE(search.isEmpty());
    const Path* elem;
    float score = search.pop(&elem);
    EXPECT_GE(score, lastReturned);  // should always return elements in order
    lastReturned = score;
    EXPECT_FALSE(elem == NULL);  // we didn't popWithoutScore off a null element
    EXPECT_EQ(3, elem->factLength);  // we only did mutations
    EXPECT_FALSE(elem->fact == NULL);  // the element's fact is not null
    EXPECT_FALSE(elem->parent == NULL);  // the element has a parent
    for (int k = 0; k < elem->factLength; ++k) {
      EXPECT_GE(elem->fact[k], 0);  // the word is positive (should always be the case anyways)
      EXPECT_LT(elem->fact[k], 120000);  // the word is within bounds (hopefully catches memory corruption errors)
    }
    EXPECT_FALSE(search.debugGet(i)->fact == NULL);  // the internal memory state is not corrupted
  }
  // Final checks
  EXPECT_TRUE(search.isEmpty());
}


//
// Cache Strategy (None)
//
class CacheStrategyNoneTest : public ::testing::Test {
 protected:
  virtual void SetUp() {
    // Add base path element
    root = new Path(&lemursHaveTails_[0], lemursHaveTails_.size());
    lemursHaveTails_ = lemursHaveTails();
    animalsHaveTails_ = animalsHaveTails();
    catsHaveTails_ = catsHaveTails();
  }

  virtual void TearDown() {
    delete root;
  }

  CacheStrategyNone cache;
  Path* root;

  std::vector<word> lemursHaveTails_;
  std::vector<word> animalsHaveTails_;
  std::vector<word> catsHaveTails_;
};

// Make sure that we don't see a node in an empty cache
TEST_F(CacheStrategyNoneTest, AlwaysNotSeen) {
  EXPECT_FALSE(cache.isSeen(*root));
}

// Make sure adding a node to the noop cache has no effect
TEST_F(CacheStrategyNoneTest, AddHasNoEffect) {
  EXPECT_FALSE(cache.isSeen(*root));
  cache.add(*root);
  EXPECT_FALSE(cache.isSeen(*root));
}

