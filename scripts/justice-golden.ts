// Writes app/src/test/resources/justice/golden.json: the TypeScript justice engine's layouts for
// the library's own test inputs and its mobile-article fixture. JusticeGoldenTests asserts the
// Kotlin port reproduces every line.
//
//   git clone https://github.com/kitlangton/justice .justice-reference
//   git -C .justice-reference checkout 86dbc6d
//   bun scripts/justice-golden.ts
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { execSync } from "node:child_process";

const reference = resolve(process.env.JUSTICE_REFERENCE ?? ".justice-reference");
const engine = await import(`${reference}/src/engine.ts`);
const { mobileArticle } = await import(`${reference}/test/fixtures/mobile-article.ts`);
const { defaults, prepare, solve, withHyphenation, withOpticalMargins } = engine;
type Prepared = import("../.justice-reference/src/engine").Prepared;
type Options = import("../.justice-reference/src/engine").Options;
type Layout = import("../.justice-reference/src/engine").Layout;

const measures: Record<string, (text: string) => number> = {
  // engine.test.ts, refinements.test.ts, static.test.ts
  eight: text => Array.from(text).reduce((n, c) => n + (c === " " ? 4 : 8), 0),
  // hyphenation.test.ts: deliberately non-additive shaping
  shaped: s => [...s].reduce((n, c) => n + (c === " " ? 4 : 8), 0) - (s.match(/a-/g)?.length ?? 0) * 2,
};
const opticals: Record<string, (text: string, index: number) => { start: number; end: number }> = {
  oracle: (_, index) => ({ start: (index % 3) * 1.7, end: (index % 2) * 2.1 }),
  thus: () => ({ start: 2, end: 3 }),
};

const seeded = (seed: number) => () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 2 ** 32; };
const split = (word: string) => word.match(/.{1,3}/gu)!;

const array = (values?: ArrayLike<number>) => values ? Array.from(values) : null;
function serialize(p: Prepared) {
  return {
    words: p.words,
    widths: array(p.widths),
    characters: array(p.characters),
    endHangs: array(p.endHangs),
    startHangs: array(p.startHangs),
    space: p.space,
    startProtrusions: array(p.startProtrusions),
    endProtrusions: array(p.endProtrusions),
    hyphenation: p.hyphenation ? Array.from(p.hyphenation, f => f ? {
      offsets: f.offsets,
      widths: array(f.widths),
      hyphenWidths: array(f.hyphenWidths),
      characters: array(f.characters),
      startProtrusions: array(f.startProtrusions),
      hyphenProtrusion: f.hyphenProtrusion ?? null,
      explicit: f.explicit ?? null,
    } : null) : null,
  };
}
const layout = (l: Layout) => ({
  cost: l.cost,
  candidates: l.candidates,
  lines: l.lines.map(line => ({
    start: line.start, end: line.end, width: line.width, natural: line.natural, wordSpacing: line.wordSpacing,
    tracking: line.tracking, hanging: line.hanging, opening: line.opening, residual: line.residual,
    relaxed: line.relaxed, cost: line.cost, last: line.last, startOffset: line.startOffset ?? null,
    endOffset: line.endOffset ?? null, hyphenated: line.hyphenated ?? false, fitness: line.fitness,
  })),
});

const prepared: Record<string, ReturnType<typeof serialize>> = {};
const ids = new Map<string, string>();
const cases: object[] = [];

function intern(p: Prepared, name?: string) {
  const value = serialize(p), key = JSON.stringify(value);
  let id = ids.get(key);
  if (!id) {
    id = name ?? `p${ids.size}`;
    ids.set(key, id);
    prepared[id] = value;
  }
  return id;
}

/** Only what differs from the defaults, so a case reads as its test wrote it. */
const changes = (policy: Partial<Options>) =>
  Object.fromEntries(Object.entries(policy).filter(([key, value]) => defaults[key as keyof Options] !== value));

/** A case whose input the Kotlin port prepares itself from text, then checks against ours. */
function fromText(name: string, text: string, width: number | number[], policy: Partial<Options>, measure = "eight", optical?: string) {
  const plain = prepare(text, measures[measure]);
  const p = optical ? withOpticalMargins(plain, opticals[optical]) : plain;
  cases.push({ name, source: "text", text, measure, optical: optical ?? null, prepared: intern(p), width, policy: changes(policy), layout: layout(solve(p, width, policy)) });
}

/** A case solved from a prepared paragraph the port cannot build (fixture or dictionary breaks). */
function fromPrepared(name: string, id: string, p: Prepared, width: number | number[], policy: Partial<Options>, extra: object = {}) {
  cases.push({ name, source: "prepared", prepared: intern(p, id), width, policy: changes(policy), ...extra, layout: layout(solve(p, width, policy)) });
}

const mobile = mobileArticle as Prepared;
const mobileWhole = { ...mobile, hyphenation: undefined } as Prepared;
const mobileWidths = Array.from({ length: 46 }, (_, i) => 268 + 4 * i).concat([350, 448]);
for (const width of mobileWidths) fromPrepared(`mobile-article/hyphenated/${width}`, "mobile", mobile, width, {});
for (const width of mobileWidths.filter((_, i) => i % 3 === 0).concat([350])) fromPrepared(`mobile-article/whole/${width}`, "mobile-whole", mobileWhole, width, {});
for (const [id, p] of [["mobile", mobile], ["mobile-whole", mobileWhole]] as const) {
  for (const width of [300, 350, 400]) fromPrepared(`mobile-article/${id}/strict/${width}`, id, p, width, { mode: "strict" });
  fromPrepared(`mobile-article/${id}/no-emergency/350`, id, p, 350, { emergencyStretch: 0 });
  fromPrepared(`mobile-article/${id}/independent/350`, id, p, 350, { adjacentPenalty: 0 });
  fromPrepared(`mobile-article/${id}/soft-ending/350`, id, p, 350, { ending: "soft" });
  fromPrepared(`mobile-article/${id}/indent`, id, p, [318, 350], {});
  fromPrepared(`mobile-article/${id}/drop-cap`, id, p, [290, 290, 350], {});
}

// engine.test.ts
const counting = "one two three four five six seven eight nine ten eleven twelve";
fromText("engine/indent", counting, [160, 200], { tracking: 0 });
fromText("engine/drop-cap", counting, [120, 120, 200], { tracking: 0 });
fromText("engine/counting", counting, 200, {});
const hyphens = "a well-known state-of-the-art thing";
fromText("engine/explicit-hyphens", hyphens, 60, { tracking: 0 });
fromText("engine/explicit-hyphens/cheap", hyphens, 60, { tracking: 0, explicitHyphenPenalty: 0 });
fromText("engine/explicit-hyphens/dear", hyphens, 60, { tracking: 0, explicitHyphenPenalty: 500 });
fromText("engine/explicit-hyphens/wide", hyphens, 300, {});
fromText("engine/not-explicit", "-5 to 5- and -- or a-", 100, {});
fromPrepared("engine/merged-dictionary", "merged", withHyphenation(prepare(hyphens, measures.eight), (word: string) => word === "well-known" ? ["well-kn", "own"] : [word], measures.eight), 60, { tracking: 0 });
fromPrepared("engine/explicit-and-dictionary", "long", withHyphenation(prepare("abc-defghijkl", measures.eight), () => ["abc-def", "ghijkl"], measures.eight), 60, { tracking: 0 });
fromText("engine/loose/strict", "aaa bbb ccccccc", 70, { mode: "strict", tracking: 0 });
fromText("engine/loose/balanced", "aaa bbb ccccccc", 70, { tracking: 0 });
fromText("engine/tight/strict", "aaa bbb", 50, { mode: "strict", tracking: 0 });
fromText("engine/tight/balanced", "aaa bbb", 50, { tracking: 0, shrink: 0.5 });
fromText("engine/overflow", "tiny extraordinarilylongword tiny", 40, { tracking: 0 });
for (const hanging of [0, 0.5, 1]) fromText(`engine/hang/${hanging}`, "word.", 32, { tracking: 0, hanging });
fromText("engine/trailing-punctuation", "“one” can’t v1.2 done.”", 1000, {});
fromText("engine/trailing-punctuation/narrow", "“one” can’t v1.2 done.”", 70, {});
fromText("engine/nbsp", "  walk\t10\u00a0km\n today  ", 20, {});
for (const [width, policy] of [[40, {}], [100, {}], [80, { stretch: 1 }]] as const) fromText(`engine/reuse/${width}`, "one two one two", width, policy);
fromText("engine/empty", " \n\t", 100, {});

// refinements.test.ts
const quiet = "A quiet paragraph can become much more comfortable when its lines share a reasonably even rhythm of spaces instead of alternating between very tight and very loose arrangements.";
fromText("refinements/independent", quiet, 300, { adjacentPenalty: 0 });
fromText("refinements/adjacent", quiet, 300, {});
fromText("refinements/adjacent/strict", quiet, 260, { mode: "strict" });
fromText("refinements/optical", "Thus.", 100, {}, "eight", "thus");
fromText("refinements/optical/off", "Thus.", 100, { protrusion: 0 }, "eight", "thus");
fromText("refinements/optical/half", "Thus.", 100, { protrusion: 0.5, hanging: 0 }, "eight", "thus");
const continuation = withOpticalMargins(withHyphenation(prepare("abcdefghijklmnopqr", measures.eight), (word: string) => word.match(/.{3}/g)!, measures.eight),
  (text: string) => ({ start: text.startsWith("a") ? 1 : 2, end: text === "-" ? 4 : 0 }));
for (const width of [54, 100, 250]) fromPrepared(`refinements/continuation/${width}`, "continuation", continuation, width, { tracking: 0 });

// hyphenation.test.ts
const runs = prepare("abcdefghijklmnopqr", measures.shaped);
const splitRuns = withHyphenation(runs, split, measures.shaped);
fromPrepared("hyphenation/successive", "successive", splitRuns, 56, { tracking: 0 });
fromPrepared("hyphenation/successive/unpenalized", "successive", splitRuns, 56, { tracking: 0, hyphenPenalty: 0, consecutiveHyphenPenalty: 0, finalHyphenPenalty: 0 });
const original = prepare("aaa bbbbbbb ccc", measures.shaped);
fromPrepared("hyphenation/unchanged", "unchanged", withHyphenation(original, (word: string) => [word], measures.shaped), 70, {});
const splitOriginal = withHyphenation(original, split, measures.shaped);
for (const width of [500, 80, 120, 70]) fromPrepared(`hyphenation/whole-words/${width}`, "split-original", splitOriginal, width, {});
{
  const random = seeded(17);
  for (let i = 0; i < 100; i++) {
    const text = Array.from({ length: 2 + i % 3 }, () => "a".repeat(3 + Math.floor(random() * 7))).join(" ");
    const width = 30 + random() * 150;
    const o: Options = { ...defaults, mode: i % 2 ? "strict" : "balanced", ending: i % 3 ? "fit" : "soft", tracking: random(), shrink: random(), hyphenPenalty: random() * 100, consecutiveHyphenPenalty: random() * 300, finalHyphenPenalty: random() * 300 };
    const hyphenated = withHyphenation(prepare(text, measures.shaped), split, measures.shaped);
    const optical = i % 3 ? undefined : (text: string, index: number) => ({ start: 0.3 * (text.charCodeAt(0) % 5) + index * 0.1, end: text.endsWith("-") ? 2.5 : 0.4 * index });
    const p = optical ? withOpticalMargins(hyphenated, optical) : hyphenated;
    fromPrepared(`hyphenation/oracle/${i}`, `hyphenation-oracle-${i}`, p, width, o, { text, optical: optical ? "hyphenation-oracle" : null });
  }
}

// static.test.ts paragraph, solved directly
const typesetting = withOpticalMargins(withHyphenation(prepare("Thus, building responsive paragraphs requires careful typesetting. Keep every source word intact.", measures.eight), (word: string) => word === "typesetting." ? ["type", "set", "ting."] : [word], measures.eight), () => ({ start: 1, end: 2 }));
for (const width of [90, 120.5, 200, 400]) fromPrepared(`static/${width}`, "typesetting", typesetting, width, {});

// engine.test.ts random oracles, both modes
{
  const random = seeded(42);
  for (let i = 0; i < 500; i++) {
    const text = Array.from({ length: 2 + Math.floor(random() * 8) }, () => (random() < 0.2 ? "“" : "") + "w".repeat(1 + Math.floor(random() * 14)) + ["", "", ".", ",", ".”"][Math.floor(random() * 5)]).join(" ");
    const optical = i % 3 ? undefined : "oracle";
    const width = 20 + Math.floor(random() * 300);
    const o: Options = { ...defaults, mode: "strict", ending: i % 2 ? "soft" : "fit", emergencyStretch: i % 3 ? random() * 0.2 : 0, opening: random(), stretch: random() * 2, shrink: random(), tracking: i % 10 === 0 ? 10 : random(), compressionPenalty: random() * 4, hanging: random(), lastLine: random(), widowPenalty: random() * 3000 };
    if (i >= 100) continue;
    fromText(`engine/oracle/${i}/strict`, text, width, o, "eight", optical);
    fromText(`engine/oracle/${i}/balanced`, text, width, { ...o, mode: "balanced" }, "eight", optical);
  }
}
{
  const random = seeded(7);
  for (let i = 0; i < 200; i++) {
    const text = Array.from({ length: 3 + Math.floor(random() * 8) }, () => (random() < 0.2 ? "“" : "") + "w".repeat(1 + Math.floor(random() * 12)) + ["", "", ".", ","][Math.floor(random() * 4)]).join(" ");
    const widths = Array.from({ length: 1 + Math.floor(random() * 4) }, () => 30 + Math.floor(random() * 200));
    const o: Options = { ...defaults, mode: i % 2 ? "strict" : "balanced", emergencyStretch: i % 3 ? random() * 0.2 : 0, stretch: random() * 2, shrink: random(), tracking: random(), opening: random(), hanging: random(), lastLine: random(), widowPenalty: random() * 2000, adjacentPenalty: i % 4 ? 100 : 0 };
    if (i >= 60) continue;
    fromText(`engine/per-line/${i}`, text, widths, o);
  }
}

const commit = execSync("git rev-parse HEAD", { cwd: reference }).toString().trim();
const finite = (_: string, value: unknown) => typeof value === "number" && !Number.isFinite(value) ? String(value) : value;
const output = resolve("app/src/test/resources/justice/golden.json");
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, JSON.stringify({ reference: { repository: "https://github.com/kitlangton/justice", commit }, defaults, prepared, cases }, finite) + "\n");
console.log(`${cases.length} cases, ${Object.keys(prepared).length} prepared paragraphs -> ${output}`);
