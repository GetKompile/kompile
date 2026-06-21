# Per-Variable Variance Estimation and Information Gain

> **STATUS (2026-06-21): §3.1–§3.6 + §1.5 + §1.6 ALL IMPLEMENTED + tested** in `ai.kompile.graph.reasoning.uncertainty` (infra-free lib): `VariableUncertainty`, `BayesianUncertaintyEstimator` (§3.2), `PslUncertaintyAdapter` (§3.3, incl. `bald` §1.5), `InformationGainEstimator` (§3.4 exact EIG via VE), `PslInformationGainApproximator` (§3.5 `sampleSplitEIG` + `covarianceProxyEIG` — enabled by exposing `PslMarginalInference.Result.rawSamples()`, a back-compat 4th record field), `SensitivityAnalyzer` (§1.6 posterior-shift), `ValueOfInformation` (§3.6 unified Bayesian+PSL "what to observe next"). `UncertaintyEstimatorTest` (9 tests); lib **312 green**. Deferred only: §1.5 BALD-as-MI on the Bayesian path (PSL BALD done), MEBN-path model-uncertainty injection.

**Purpose:** For each variable/node in a reasoning graph (Bayesian RV, PSL atom, or graph entity), how do we estimate (a) its **variance / uncertainty** and (b) its **information gain / informativeness** — i.e., which nodes are most uncertain, and which, if observed, would most reduce uncertainty about target nodes? This underpins active KB maintenance (what to verify or observe next) and uncertainty-aware reasoning.

---

## 1. Theoretical Techniques

### 1.1 Per-Variable Marginal Variance

For a **binary random variable** with marginal P(X = true) = p, the Bernoulli variance is:

```
Var(X) = p(1 - p)
```

This is maximised at p = 0.5 (maximum uncertainty) and zero at p = 0 or 1 (certainty). This is the natural per-node uncertainty measure for the Bayesian path because `VariableElimination.queryAll` already returns exactly the marginal p for every variable.

For **multi-valued variables** (cardinality k), the analogous measure is the entropy (see §1.2). For a continuous variable the variance is the second central moment, or the sample variance from Monte Carlo draws.

**Epistemic vs. aleatoric decomposition.**
In probabilistic models with uncertain parameters, total variance decomposes as:

```
Var(X) = E_θ[Var(X | θ)] + Var_θ(E[X | θ])
       = aleatoric             + epistemic
```

The first term is irreducible noise given the parameters; the second measures how much the mean would shift if we knew the parameters exactly. For the PSL path, the perturb-and-MAP sampler runs K replicated MAP solves under Gumbel perturbation; the sample variance across those replicas is a natural approximation to total (predominantly epistemic) uncertainty over the HL-MRF energy landscape.

Reference: Papandreou & Yuille (2011), "Perturb-and-MAP Random Fields: Using Discrete Optimization to Learn and Sample from Energy Models", ICCV 2011.

---

### 1.2 Shannon Entropy Per Node

Shannon entropy for a discrete variable X with states {x_1, …, x_k}:

```
H(X) = -∑_i P(X = x_i) · log₂ P(X = x_i)      [bits]
```

For a binary variable: H = -p log₂ p - (1-p) log₂(1-p), which is maximised at p = 0.5 (1 bit) and is zero at certainty. Entropy is strictly more informative than variance for multi-valued variables (variance depends on a numeric scale; entropy does not).

**Conditional entropy** H(X | E) is the entropy of X given current evidence E, computed from the posterior P(X | E) returned by one VE query.

Reference: Shannon (1948), "A Mathematical Theory of Communication", Bell System Technical Journal. Wikipedia: https://en.wikipedia.org/wiki/Entropy_(information_theory)

---

### 1.3 Mutual Information Between Nodes

The **mutual information** between two variables X and Y is:

```
I(X; Y) = H(X) - H(X | Y)
         = H(Y) - H(Y | X)
         = ∑_{x,y} P(x,y) · log [ P(x,y) / (P(x)·P(y)) ]
```

It is symmetric and non-negative, equalling zero iff X and Y are independent. In the context of a Bayesian network:

- H(target) is the prior entropy of the target node (before observing X).
- H(target | X) is the expected posterior entropy of target after X is revealed.
- I(X; target) = H(target) - H(target | X) is the **information gain** about the target obtained by observing X.

This is formally identical to the mutual information used in feature selection, active learning, and experimental design.

Reference: Wikipedia: https://en.wikipedia.org/wiki/Mutual_information  
BayesiaLab e-book: "Uncertainty, Entropy, and Mutual Information", https://www.bayesia.com/bayesialab/e-book/chapter-5-bayesian-networks-and-data/uncertainty-entropy-and-mutual-information  
Conditioning, Mutual Information, and Information Gain (Springer, 2022): https://link.springer.com/chapter/10.1007/978-3-662-65875-8_12

---

### 1.4 Value of Information / Expected Value of Perfect Information (EVPI)

**Value of Information (VoI)** is the maximum a decision-maker would pay for information before making a decision (Howard 1966, "Information Value Theory", IEEE Trans. Systems Science and Cybernetics 2(1):22–26; Wikipedia: https://en.wikipedia.org/wiki/Value_of_information).

For pure inference (no utility function), the VoI of observing variable X toward a target T reduces to the expected information gain:

```
VoI(X → T) = H(T) - E_{x ~ P(X)}[H(T | X = x)]
            = I(X; T)
```

In Bayesian networks this is computable exactly:

1. Run VE to get current posterior P(T | E) → entropy H(T | E).
2. For each state x_i of variable X, run VE to get P(T | E, X = x_i) → conditional entropy H(T | E, X = x_i).
3. Weighted average: E_x[H(T | E, X)] = ∑_i P(X = x_i | E) · H(T | E, X = x_i).
4. EIG(X → T) = H(T | E) - E_x[H(T | E, X)].

The variable X* that maximises EIG(X → T) is the next best node to observe — the greedy maximum-MI acquisition function used in active learning.

For a network with N variables, this requires O(N) VE queries per evaluation, each of complexity O(n · d^w). For sparse KG-derived networks (low treewidth) this is tractable.

---

### 1.5 BALD — Bayesian Active Learning by Disagreement

BALD (Houlsby et al., 2011; Gal, Islam & Ghahramani 2017, "Deep Bayesian Active Learning with Image Data", arXiv:1703.02910, https://arxiv.org/abs/1703.02910) selects the query that maximises:

```
BALD(X) = H(Y | X, D_train) - E_{θ ~ p(θ|D_train)}[H(Y | X, θ)]
         = I(Y; θ | X, D_train)
```

The first term is the total predictive entropy; the second is the expected entropy under each parameter draw. The difference is the mutual information between the label and the model parameters — it measures the epistemic (model) uncertainty. In the PSL perturb-and-MAP setting, the MAP samples play the role of θ draws, so:

```
BALD_PSL(atom_x) ≈ H(soft-surrogate-entropy(x)) - (1/K) ∑_k H(atom_x in sample_k)
```

However, PSL soft-truth values are not probabilities in the Shannon sense (see §3 pitfalls), so this is an approximation.

---

### 1.6 Sensitivity Analysis in Bayesian Networks

Bayesian network sensitivity analysis asks: how much does a target posterior P(T | E) change when a CPT parameter θ (or an evidence value) is varied? The one-way sensitivity function is linear in each CPT parameter (Koller & Friedman, "Probabilistic Graphical Models", MIT Press 2009, §21). A co-variation scheme (e.g. proportional variation) preserves sum-to-one constraints.

**Relation to information gain:** Sensitivity with respect to evidence node X captures a related but different quantity — it measures local gradient of the posterior rather than expected reduction in entropy. For binary evidence, sensitivity = |P(T | X = 1, E) - P(T | E)| weighted by P(X | E), which approximates EIG when the posterior is nearly linear. For practical active KB maintenance, the full EIG formulation is preferred because it is symmetric, non-negative, and unit-consistent.

---

### 1.7 Efficient Computation: One Pass for All Marginals

Getting marginals for all N variables from a single inference pass is the job of **belief propagation** (exact on trees, approximate on general DAGs) or the **all-marginals VE** (also called the junction-tree / clique-tree algorithm). The idea:

1. Build the clique tree (junction tree) of the factor graph.
2. Run upward and downward message passes (two sweeps).
3. Every clique marginal (and hence every variable marginal) is read off in O(d^w) time per clique.

Total complexity: O(n · d^w), same as a single VE query — computing all marginals costs no more than computing one. This is the standard approach in libraries like libDAI, pomegranate, pgmpy.

For the current **Kompile VE implementation**, `queryAll` already iterates one VE pass per variable (N queries, each O(n·d^w) = O(N²·d^w) total). For small networks (< 50 nodes) the current approach is fine; for larger networks, a junction-tree upgrade would bring it to O(N·d^w).

**Incremental info-gain.** After observing one variable X = x, the KG is updated with that evidence and all marginals are re-computed. A full re-query is needed unless a message-passing algorithm supports incremental evidence retraction; the junction tree supports this in principle.

---

## 2. What the Current Kompile Library Already Produces

### 2.1 Bayesian Path (`bayesian/`)

| Class | What it provides |
|---|---|
| `BayesianNetwork` | DAG of `BayesianNode`s, topological order, Markov blanket |
| `Factor` | product / marginalize / normalize / reduce — core factor algebra |
| `VariableElimination.query(net, var, evidence)` | P(var \| evidence) for one variable |
| `VariableElimination.queryAll(net, evidence)` | Map<String, Double> of P(X_i = true \| evidence) for all variables |
| `VariableElimination.mostProbableExplanation` | argmax joint assignment |
| `NoisyOrCpt` | compact CPT parameterisation, causal strength from edge weight/confidence |

**What's already computable with no new code:**  
- Per-variable posterior p_i = `queryAll` → Var(X_i) = p_i(1-p_i), H(X_i) = binary_entropy(p_i)  
- These just need a caller that computes the formulas over the map returned by `queryAll`.

**What's missing:**
- No entropy / variance computed from the marginals anywhere.
- No information gain / EIG computation (requires N+1 VE queries per target-candidate pair).
- No "next best node to observe" selector.
- No joint factor over (X, T) for computing I(X; T) exactly (would need a 2-variable query or joint factor extraction).

### 2.2 PSL Path (`psl/`)

| Class | What it provides |
|---|---|
| `PslMarginalInference` | Perturb-and-MAP sampling: K MAP solutions → per-atom mean + variance |
| `AtomMarginal` | `atomKey`, `mean`, `variance`, `samples`, `stdDev()`, `halfWidth95()` |
| `HlMrfMapInference` | Point MAP solution (the baseline) |

**What's already computable:**
- Per-atom variance is already materialised in `AtomMarginal.variance()`.
- Sample mean is already available.

**What's missing:**
- No entropy computed from the sample distribution (trivial to approximate as `H ≈ -p log p - (1-p) log(1-p)` where p = mean, or from histogram of samples if cardinality > 2).
- No cross-atom information gain / VoI (requires conditional re-runs or a cross-atom sample covariance approach — see §3.3).
- No "most uncertain atom" ranking API.

### 2.3 FOL / MEBN Path (`fol/`)

`EntailmentRecord` carries only a point-estimate `posterior` (P or soft-truth) and provenance metadata. No variance or distribution. This path would need model-level uncertainty injection (e.g., perturbing rule weights or sampling from MEBN's MFrag distribution) before per-variable variance is available.

### 2.4 Graph Entity Layer (`model/`)

`GraphEntity` carries `weight()` and `confidence()` as scalars, neither of which is a distributional uncertainty measure. Variance/entropy information from the Bayesian or PSL layers must be attached externally (e.g., via `attributes()`) when surfaced at the graph entity level.

---

## 3. Concrete Implementation Plan

### 3.1 Core Primitives: `VariableUncertainty` Record

Create `ai.kompile.graph.reasoning.uncertainty.VariableUncertainty` (a single record, no deps beyond existing classes):

```java
package ai.kompile.graph.reasoning.uncertainty;

/**
 * Distributional uncertainty summary for one variable/node.
 *
 * All fields are in natural units (variance in [0, 0.25] for Bernoulli;
 * entropy in bits; marginal in [0, 1]).
 */
public record VariableUncertainty(
    /** Variable name (Bayesian) or atom key (PSL). */
    String variableKey,
    /** Posterior marginal P(X = true | evidence) or PSL soft-truth mean. */
    double marginal,
    /** Var(X) = marginal * (1 - marginal) for Bernoulli; sample variance for PSL. */
    double variance,
    /** Shannon entropy H(X) in bits. */
    double entropyBits,
    /** Number of samples that contributed (1 for exact VE, K for PSL). */
    int samples,
    /** Which path produced this: BAYESIAN, PSL, or FOL. */
    InferencePath path
) {
    public enum InferencePath { BAYESIAN, PSL, FOL }

    /** Convenience: stdDev. */
    public double stdDev() { return Math.sqrt(variance); }

    /** Binary Shannon entropy helper. */
    public static double binaryEntropy(double p) {
        if (p <= 0 || p >= 1) return 0.0;
        return -p * log2(p) - (1 - p) * log2(1 - p);
    }

    private static double log2(double x) { return Math.log(x) / Math.log(2); }
}
```

**Effort:** ~1 day. No build changes needed — new class in a new `uncertainty` subpackage.

---

### 3.2 Bayesian Uncertainty Estimator

Create `ai.kompile.graph.reasoning.uncertainty.BayesianUncertaintyEstimator`:

```java
public class BayesianUncertaintyEstimator {

    /**
     * Compute per-variable uncertainty for all non-evidence nodes,
     * using a single queryAll call (N VE queries, one per variable).
     */
    public static Map<String, VariableUncertainty> allUncertainties(
            BayesianNetwork net, Map<String, Integer> evidence) {
        Map<String, Double> marginals = VariableElimination.queryAll(net, evidence);
        Map<String, VariableUncertainty> result = new LinkedHashMap<>();
        for (var entry : marginals.entrySet()) {
            String var = entry.getKey();
            double p = entry.getValue();
            double variance = evidence.containsKey(var) ? 0.0 : p * (1 - p);
            double entropy  = evidence.containsKey(var) ? 0.0
                            : VariableUncertainty.binaryEntropy(p);
            result.put(var, new VariableUncertainty(
                var, p, variance, entropy, 1,
                VariableUncertainty.InferencePath.BAYESIAN));
        }
        return result;
    }

    /**
     * Rank non-evidence nodes by entropy (highest first = most uncertain).
     */
    public static List<VariableUncertainty> rankByUncertainty(
            BayesianNetwork net, Map<String, Integer> evidence) {
        return allUncertainties(net, evidence).values().stream()
            .filter(u -> !evidence.containsKey(u.variableKey()))
            .sorted(Comparator.comparingDouble(VariableUncertainty::entropyBits).reversed())
            .toList();
    }
}
```

**Effort:** ~1 day (including unit tests).

---

### 3.3 PSL Uncertainty Adapter

Create `ai.kompile.graph.reasoning.uncertainty.PslUncertaintyAdapter`:

```java
public class PslUncertaintyAdapter {

    /**
     * Convert PslMarginalInference.Result to per-atom VariableUncertainty.
     *
     * Note: PSL soft-truth is NOT a probability. The entropy computed here
     * uses the mean as if it were a Bernoulli p — this is a heuristic proxy,
     * not a true information-theoretic entropy (see pitfalls §3.7).
     */
    public static Map<String, VariableUncertainty> fromMarginals(
            PslMarginalInference.Result result) {
        Map<String, VariableUncertainty> out = new LinkedHashMap<>();
        for (var entry : result.marginals().entrySet()) {
            AtomMarginal am = entry.getValue();
            double p = am.mean();
            double entropy = VariableUncertainty.binaryEntropy(p);
            out.put(am.atomKey(), new VariableUncertainty(
                am.atomKey(), p, am.variance(), entropy, am.samples(),
                VariableUncertainty.InferencePath.PSL));
        }
        return out;
    }

    /**
     * Rank atoms by variance (highest = most uncertain under PSL sampling).
     */
    public static List<VariableUncertainty> rankByVariance(
            PslMarginalInference.Result result) {
        return fromMarginals(result).values().stream()
            .sorted(Comparator.comparingDouble(VariableUncertainty::variance).reversed())
            .toList();
    }
}
```

**Effort:** ~half a day (trivial adaption of existing AtomMarginal fields).

---

### 3.4 Information Gain Estimator (Bayesian Path — Exact)

Create `ai.kompile.graph.reasoning.uncertainty.InformationGainEstimator`:

```java
public class InformationGainEstimator {

    /**
     * Compute the Expected Information Gain (EIG) of observing variable
     * `candidate` about target variable `target`, given current evidence.
     *
     *   EIG(candidate → target) = H(target | evidence)
     *                           - E_{c ~ P(candidate|evidence)}[H(target | evidence, candidate=c)]
     *
     * Requires 1 + cardinality(candidate) additional VE queries on top of
     * the baseline entropy already computed.
     *
     * @param net       the Bayesian network
     * @param evidence  current evidence
     * @param candidate the variable whose observation we are evaluating
     * @param target    the target variable we care about reducing uncertainty for
     * @return EIG in bits (>= 0)
     */
    public static double expectedInformationGain(
            BayesianNetwork net,
            Map<String, Integer> evidence,
            String candidate,
            String target) {

        // 1. Baseline entropy of target under current evidence
        Factor targetMarginal = VariableElimination.query(net, target, evidence);
        double baselineEntropy = shannonEntropyBits(targetMarginal.getValues());

        // 2. P(candidate | evidence) — marginal of the candidate
        Factor candidateMarginal = VariableElimination.query(net, candidate, evidence);
        double[] pCandidate = candidateMarginal.getValues();

        // 3. For each state c of candidate, compute H(target | evidence, candidate=c)
        double conditionalEntropy = 0.0;
        for (int c = 0; c < pCandidate.length; c++) {
            if (pCandidate[c] < 1e-12) continue;
            Map<String, Integer> extendedEvidence = new HashMap<>(evidence);
            extendedEvidence.put(candidate, c);
            Factor targetGivenC = VariableElimination.query(net, target, extendedEvidence);
            double h = shannonEntropyBits(targetGivenC.getValues());
            conditionalEntropy += pCandidate[c] * h;
        }

        return Math.max(0.0, baselineEntropy - conditionalEntropy);
    }

    /**
     * Compute EIG for every non-evidence, non-target variable toward `target`,
     * returning them sorted by EIG descending (highest = observe this first).
     *
     * This is the "next best node to observe" ranking — the greedy max-MI
     * active KB acquisition function.
     */
    public static List<Map.Entry<String, Double>> rankCandidatesByEIG(
            BayesianNetwork net,
            Map<String, Integer> evidence,
            String target) {

        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (BayesianNode node : net.getNodes()) {
            String var = node.getVariableName();
            if (evidence.containsKey(var) || var.equals(target)) continue;
            double eig = expectedInformationGain(net, evidence, var, target);
            ranked.add(Map.entry(var, eig));
        }
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return ranked;
    }

    private static double shannonEntropyBits(double[] probs) {
        double h = 0.0;
        for (double p : probs) {
            if (p > 1e-12) h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }
}
```

**Complexity:** For N candidates and binary variables, this is N × 3 VE queries = 3N VE calls per target per invocation.  
**Effort:** ~2 days including tests.

---

### 3.5 PSL Information Gain Approximation

For PSL, exact VE is not available. Instead, exploit the already-collected K samples:

```
Approx_EIG_PSL(candidate → target) ≈ H_proxy(target mean)
    − E_{samples}[H_proxy(target | candidate bin determined by sample)]
```

Concretely: split the K perturb-and-MAP samples into two bins based on whether atom_candidate > 0.5 or ≤ 0.5 in each sample. Compute the soft-entropy of atom_target within each bin using the bin's mean. Weight by the fraction of samples in each bin. This is a covariance-from-samples approximation.

Create `ai.kompile.graph.reasoning.uncertainty.PslInformationGainApproximator`:

```java
public class PslInformationGainApproximator {

    /**
     * Approximate EIG of observing `candidate` toward `target` using raw sample lists.
     * Raw samples (List<Map<String,Double>>) are collected from PslMarginalInference
     * but currently not stored — see Phase 2 work in §4.
     *
     * Without raw samples, a covariance proxy may be computed as:
     *   proxy_EIG ≈ |Cov(candidate, target)| / sqrt(Var(candidate) * Var(target))
     * (the Pearson correlation of the sample vectors), which is monotonic in MI for
     * Gaussian approximations. This is a rough guide, not a calibrated bit count.
     */
    public static double covarianceProxyEIG(
            double candidateMean, double candidateVariance,
            double targetMean, double targetVariance,
            double sampleCovariance) {
        double denom = Math.sqrt(candidateVariance * targetVariance);
        if (denom < 1e-12) return 0.0;
        double r = sampleCovariance / denom;
        // Mutual information of a bivariate Gaussian (upper bound for binary): -0.5 ln(1-r^2)
        return Math.max(0.0, -0.5 * Math.log(1 - r * r) / Math.log(2));
    }
}
```

To enable the covariance approximation, `PslMarginalInference.solve` must expose the raw per-sample matrix (currently discarded after accumulation). A minor enhancement to `PslMarginalInference` is needed: return a `List<Map<String,Double>> rawSamples` alongside the current statistics. See §4 Priority 3.

---

### 3.6 Value of Information Selector (`ValueOfInformation`)

Create a unified, path-agnostic API:

```java
package ai.kompile.graph.reasoning.uncertainty;

/**
 * Ranks unobserved nodes by their expected value of observation toward a target.
 *
 * For Bayesian networks: uses exact EIG from InformationGainEstimator.
 * For PSL: uses the covariance proxy or, if raw samples are available, the
 *           sample-split approximate EIG from PslInformationGainApproximator.
 *
 * Usage pattern:
 *   List<VoIResult> ranking = ValueOfInformation
 *       .forBayesianNetwork(net, evidence, "target_var")
 *       .ranked();
 *   String nextToObserve = ranking.get(0).variableKey();
 */
public class ValueOfInformation {

    public record VoIResult(
        String variableKey,
        double expectedInformationGain,   // bits
        double currentEntropy,            // bits — how uncertain the candidate currently is
        VariableUncertainty.InferencePath path
    ) {}

    public static List<VoIResult> forBayesianNetwork(
            BayesianNetwork net,
            Map<String, Integer> evidence,
            String targetVariable) { ... }

    public static List<VoIResult> forPslResult(
            PslMarginalInference.Result result,
            String targetAtomKey) { ... }
}
```

**Effort:** ~1–2 days for the Bayesian path (delegates to `InformationGainEstimator`); ~1 additional day for PSL path once raw samples are available.

---

### 3.7 Pitfalls and Caveats

**PSL soft-truth is not a probability.** The output of HL-MRF inference is a MAP assignment in [0, 1] under Lukasiewicz logic — not a posterior probability. The entropy formula H = -p log p - (1-p) log(1-p) applied to the mean soft-truth is a useful heuristic proxy (and consistent with the convention used by PSL tools like the original LINQS/psl library), but it does not have Shannon's axiomatic foundations when p is a soft constraint satisfaction level rather than a probability. Callers should annotate VoI results with `path = PSL` and note this caveat in documentation.

**`queryAll` runs N independent VE passes.** The current implementation calls `VariableElimination.query` once per node, each performing a full factor reduction and elimination sweep. For N nodes and a network with treewidth w, this is O(N² d^w) vs the optimal O(N d^w) from a single junction-tree pass. For N < 50 (typical KG-derived sub-graphs after localization) this is fine. If the network grows beyond ~100 nodes, a junction-tree upgrade should be planned.

**Information gain is additive only in the independent case.** Greedy selection of the top-K candidates one at a time (observe node 1, re-query, select node 2, …) can be suboptimal compared to joint selection of a batch. The greedy approach has a (1 - 1/e) approximation guarantee when the EIG function is submodular (which it is for Bayesian networks with discrete variables and independent observations). This is sufficient for active KB maintenance.

**Evidence ordering matters.** VoI rankings shift as observations accumulate. The selector should be called after each new observation is incorporated.

**Binary variable assumption in current VE.** `VariableElimination.query` returns a 2-element factor for binary nodes (index 0 = FALSE, index 1 = TRUE). The entropy and EIG formulas above generalise directly to multi-valued variables via the full `Factor.getValues()` array; no code change is needed.

**Computational cost of `rankCandidatesByEIG`.** For N candidates and a target, this runs 3N VE queries. For N = 50 and a sparse network, each VE query is fast (< 1 ms); the full ranking completes in < 200 ms. For N = 200, profiling is needed.

---

## 4. Prioritised Implementation Plan

### Priority 1 — Variance and Entropy for All Variables (2–3 days, no new dependencies)

1. Create `uncertainty/` subpackage in `kompile-graph-reasoning`.
2. Implement `VariableUncertainty` record (§3.1).
3. Implement `BayesianUncertaintyEstimator.allUncertainties` + `rankByUncertainty` (§3.2).
4. Implement `PslUncertaintyAdapter.fromMarginals` + `rankByVariance` (§3.3).
5. Unit tests: BN with known marginals → verify Var = p(1-p), H = binary_entropy; PSL mock result → verify adapter.

Deliverable: Any caller can ask "which nodes are most uncertain?" after an inference pass.

### Priority 2 — Exact Information Gain for Bayesian Path (2–3 days)

1. Implement `InformationGainEstimator.expectedInformationGain` (§3.4).
2. Implement `InformationGainEstimator.rankCandidatesByEIG` (§3.4).
3. Unit test: 3-node BN (A → C ← B), target=C, candidates={A,B}; verify I(A;C) + I(B;C) by formula.
4. Integration test: confirm ranking returns the node most informationally coupled to the target.

Deliverable: "What single unobserved node should we verify next to learn the most about target T?" — answered exactly on the Bayesian path.

### Priority 3 — PSL Approximate Info-Gain via Raw Samples (2 days)

1. Extend `PslMarginalInference.Result` to carry `List<Map<String,Double>> rawSamples` (the per-sample MAP assignments after burn-in, currently discarded).
2. Implement `PslInformationGainApproximator` using the sample-split EIG approximation (§3.5).
3. Add a Gaussian-MI upper bound fallback from the sample covariance matrix.
4. Unit test: two correlated atoms under a common cause rule → approximated EIG > 0; two independent atoms → ≈ 0.

### Priority 4 — Unified `ValueOfInformation` API (1–2 days)

1. Implement `ValueOfInformation.forBayesianNetwork` and `forPslResult` (§3.6).
2. Wire into existing `AttributionQuery` / causal attribution endpoints so a caller can request `nextBestObservation(targetId)` via REST.
3. Expose as `/api/attribution/value-of-information?target={nodeId}` returning ranked list of `VoIResult`.

### Priority 5 — Epistemic/Aleatoric Decomposition (3 days, deferred)

For the Bayesian path, epistemic uncertainty requires an ensemble or a distribution over the CPT parameters (Bayesian model averaging). The CPTs are currently point-estimated from edge weights/confidence. To support decomposition:
- Add optional Dirichlet priors on CPT parameters (Beta for binary, Dirichlet for multi-valued).
- Sample K CPT configurations from the posterior; run `queryAll` per sample; compute `Var_θ(E[X|θ])` as the inter-sample variance of the posterior mean.
- This is a 2-level sampling problem and is deferred until CPT uncertainty is better characterised from the KG.

---

## 5. Summary of New Classes and Their Location

All new classes live in:
```
kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning/
  src/main/java/ai/kompile/graph/reasoning/uncertainty/
    VariableUncertainty.java          (record; P1)
    BayesianUncertaintyEstimator.java (P1)
    PslUncertaintyAdapter.java        (P1)
    InformationGainEstimator.java     (P2)
    PslInformationGainApproximator.java (P3)
    ValueOfInformation.java           (P4)
```

Modifications to existing files:
- `PslMarginalInference.Result` — add `rawSamples` field (P3).
- Attribution REST controller (app-main) — new `/api/attribution/value-of-information` endpoint (P4).

---

## 6. References

| Source | Citation |
|---|---|
| Shannon 1948 | "A Mathematical Theory of Communication", Bell System Technical Journal — foundational entropy definition |
| Howard 1966 | "Information Value Theory", IEEE Trans. Systems Science and Cybernetics 2(1):22–26 — VoI definition |
| Papandreou & Yuille 2011 | "Perturb-and-MAP Random Fields", ICCV 2011 — perturb-and-MAP marginals on MRFs |
| Koller & Friedman 2009 | "Probabilistic Graphical Models", MIT Press — VE, junction tree, sensitivity analysis (Ch. 21) |
| Gal, Islam & Ghahramani 2017 | "Deep Bayesian Active Learning with Image Data", arXiv:1703.02910 — BALD acquisition function |
| BayesiaLab e-book | "Uncertainty, Entropy, and Mutual Information", https://www.bayesia.com/bayesialab/e-book/chapter-5-bayesian-networks-and-data/uncertainty-entropy-and-mutual-information |
| Wikipedia — Mutual Information | https://en.wikipedia.org/wiki/Mutual_information |
| Wikipedia — Value of Information | https://en.wikipedia.org/wiki/Value_of_information |
| Wikipedia — Shannon Entropy | https://en.wikipedia.org/wiki/Entropy_(information_theory) |
| Springer 2022 | "Conditioning, Mutual Information, and Information Gain", https://link.springer.com/chapter/10.1007/978-3-662-65875-8_12 |
