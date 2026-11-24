// TfL's words, taken apart: which trouble, where ("between X and Y"), and which way. A notice
// often names several sections at different severities in one sentence: "Severe delays between
// Paddington and Heathrow Terminals, and MINOR DELAYS between Hayes & Harlington and Reading,
// and between Whitechapel and Shenfield".

export type Family = "closure" | "delays" | "minor";

export interface Way {
  /** "westbound", "northbound". */
  compass?: { axis: "ew" | "ns"; sign: 1 | -1 };
  /** "towards Heathrow", "towards Battersea Power Station and Morden": any of them. */
  towards?: string[];
}

export interface Section {
  /** Each end with its alternatives: "Maidenhead / Heathrow (all terminals)". */
  from: string[];
  to: string[];
  via?: string;
  /** TfL's words for it: "between Paddington and Heathrow Terminals". */
  text: string;
  /** One way only, from this section's own words ("and westbound between …"). */
  way?: Way;
}

export interface Clause {
  family: Family;
  /** As TfL wrote it: "MINOR DELAYS". */
  phrase: string;
  sections: Section[];
  /** One way only, when TfL says so: for a clause with sections, only when every section says
   * the same way. */
  way?: Way;
  /** The clause in TfL's words, with the sentence's dates in front and its cause after. */
  text: string;
  /** Per section, when there are several. */
  sectionTexts: string[];
}

const TROUBLE = /\b(?:(part suspended|part closure|part closed|suspended|no service|not running|no trains)|(minor delays)|(severe delays|reduced service|delays)|(good service))\b/gi;
const STOP = String.raw`(?=\s*(?:[,.;:]|$)|\s+(?:due|while|whilst|because|until|after|following|owing|as|and|for|with|from|where|westbound|eastbound|northbound|southbound|towards|only|between|in\s+(?:both|either|one|each|the)|on\s+(?:the|this|these))\b)`;
const SECTION = new RegExp(String.raw`\b(?:between,?\s+([^,.;:]+?)\s+and\s+([^,.;:]+?)|from\s+([^,.;:]+?)\s+to\s+([^,.;:]+?))${STOP}`, "gi");
// A list of places after "towards" runs on through "and" and "/".
const TOWARDS = /\btowards\s+([^,.;:]+?)(?=\s*(?:[,.;:]|$)|\s+(?:due|while|whilst|because|until|after|following|owing|as|for|with|from|where|only|between|in\s+(?:both|either|one|each|the)|on\s+(?:the|this|these))\b)/i;
const CAUSE = /\b(?:due to|while we|whilst we|because|following|owing to)\b[^.]*/i;
const NOT_A_PLACE = /^(?:\d[\d:.]*|midnight|noon|(?:mon|tues|wednes|thurs|fri|satur|sun)day|january|february|march|april|may|june|july|august|september|october|november|december|start of service|end of service)\b/i;

export function clauses(reason: string): Clause[] {
  const text = reason.replace(/^\s*[A-Z][A-Za-z0-9&'’() .-]{1,48}:\s+/, "").replace(/\s+/g, " ").trim();
  const out: Clause[] = [];
  for (const sentence of text.split(/(?<=[.!?])(?<!\bSt\.)\s+(?=[A-Z])/)) {
    // "due to earlier delays" is a cause, not more trouble.
    const marks = [...sentence.matchAll(TROUBLE)].filter((m) => !(m[3]?.toLowerCase() === "delays" && /\b(?:earlier|previous|recent|ongoing|of|to|causing|any|by|from|after)\s+$/i.test(sentence.slice(0, m.index))));
    if (!marks.length) continue;
    const cause = sentence.match(CAUSE)?.[0].trim();
    // Dates and times before the first trouble ("Sunday 15 November, until 1015, no service …").
    const preamble = sentence.slice(0, marks[0].index).trim();
    marks.forEach((mark, i) => {
      if (mark[4]) return; // good service
      const family: Family = mark[1] ? "closure" : mark[2] ? "minor" : "delays";
      const end = marks[i + 1]?.index ?? sentence.length;
      const body = sentence.slice(mark.index, end);
      const scope = i === 0 ? sentence.slice(0, end) : body;
      const sections = sectionsIn(scope);
      const shout = (s: string) => (s === s.toUpperCase() ? s.toLowerCase() : s);
      const phrase = preamble ? shout(mark[0]) : capitalise(shout(mark[0]));
      const lead = preamble ? `${preamble} ` : "";
      const tidy = (s: string) => {
        let t = s.replace(/[\s,;]+(?:and|or)?[\s,;]*$/i, "").trim();
        if (cause && !t.includes(cause)) t = `${t.replace(/[.\s]+$/, "")} ${cause}`;
        return /[.!?]$/.test(t) ? t : `${t}.`;
      };
      // Every section one way, the same way, or (with none named) the clause's words.
      const way = sections.length
        ? sections.every((x) => x.way && JSON.stringify(x.way) === JSON.stringify(sections[0].way)) ? sections[0].way : undefined
        : wayIn(body);
      out.push({
        family,
        phrase: mark[0],
        sections,
        way,
        text: tidy(`${lead}${phrase}${body.slice(mark[0].length)}`),
        sectionTexts: sections.map((s) => tidy(`${lead}${phrase} ${s.text}${s.words ?? ""}`)),
      });
    });
  }
  return out;
}

function sectionsIn(text: string): (Section & { words?: string })[] {
  const found: (Section & { start: number; end: number; words?: string })[] = [];
  for (const m of text.matchAll(SECTION)) {
    const after = text.slice(m.index! + m[0].length);
    // "Trains run from X to Y only" is what still runs, not where the trouble is.
    if (/^\s+only\b/i.test(after)) continue;
    let [a, b] = m[1] !== undefined ? [m[1], m[2]] : [m[3], m[4]];
    if (NOT_A_PLACE.test(a.trim()) || NOT_A_PLACE.test(b.trim())) continue;
    let via: string | undefined;
    const v = b.match(/^(.*?)\s+via\s+(.+)$/i) ?? a.match(/^(.*?)\s+via\s+(.+)$/i);
    if (v) {
      if (b.match(/\s+via\s+/i)) b = v[1];
      else a = v[1];
      via = v[2].trim();
    }
    const alternatives = (s: string) => s.split(/\s*\/\s*|\s+or\s+/i).map((x) => x.trim()).filter(Boolean);
    found.push({ from: alternatives(a), to: alternatives(b), via, text: m[0].trim().replace(/^between,\s*/i, "between "), start: m.index!, end: m.index! + m[0].length });
  }
  // Each section's own words: the gap between two sections splits at its first ", " or "and",
  // the part before going with the section before, the rest with the one after.
  const cut = (gap: string) => {
    const at = gap.search(/,|\band\b/i);
    return at < 0 ? [gap, ""] : [gap.slice(0, at), gap.slice(at)];
  };
  return found.map((f, k) => {
    const before = k === 0 ? text.slice(0, f.start) : cut(text.slice(found[k - 1].end, f.start))[1];
    const after = k === found.length - 1 ? text.slice(f.end) : cut(text.slice(f.end, found[k + 1].start))[0];
    const own = `${before} ${f.text} ${after}`;
    const { start: _s, end: _e, ...section } = f;
    return { ...section, way: wayIn(own), words: wayWords(own) };
  });
}

/** One way only, if TfL says so; nothing for both ways or wording we can't tell. */
export function wayIn(text: string): Way | undefined {
  if (/\b(?:both|either|each)\s+directions?\b|\bclockwise\b|\banti-?clockwise\b|\b(?:inner|outer) rail\b/i.test(text)) return undefined;
  const compasses = [...text.matchAll(/\b(west|east|north|south)bound\b/gi)].map((m) => m[1].toLowerCase());
  if (new Set(compasses).size === 1) {
    const c = compasses[0];
    return { compass: { axis: c === "west" || c === "east" ? "ew" : "ns", sign: c === "east" || c === "north" ? 1 : -1 } };
  }
  if (compasses.length) return undefined;
  const towards = text.match(TOWARDS)?.[1].split(/\s*\/\s*|\s+(?:and|or)\s+|\s*,\s*/i).map((x) => x.trim()).filter(Boolean);
  return towards?.length ? { towards } : undefined;
}

function wayWords(text: string): string {
  const w = text.match(/\b(?:west|east|north|south)bound(?: only)?\b/i)?.[0] ?? text.match(TOWARDS)?.[0];
  return w ? ` ${w}` : "";
}

const capitalise = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
