---
name: write-poc-post
description: Write, publish and wire up a new proof-of-concept (POC) post on this site (dhawanrachakonda.github.io). Every claim must be backed by a source or proof, and the post must be in-depth, explaining what it covers, why, and what the reader will understand afterwards. Use this whenever the user wants a new post, write-up, blog entry, article or POC report for this repo, wants to turn a POC, experiment, benchmark or run log into a post, or asks for the social post text, even if they never say "skill" or "post.html".
---

# Write a POC post for this site

This is a personal static site (GitHub Pages, no build step). A post is a Markdown file in `posts/`, rendered by `post.html`, listed on `index.html`. `AGENTS.md` holds the site's non-negotiable rules; read it first and follow it. This skill adds a process for the two things that make or break a post here: **proof** and **depth**.

The two existing posts are the models. Read the one closest in kind before drafting:
- `posts/java-genai-proof.md`: a measured comparison (JVM vs native) with screenshots and cited sources.
- `posts/scanner-report-to-fix-branch.md`: a built system, with a "files to read" table, a runs-and-proof table, an explicit "Not proven" list, and logs in `posts/evidence/`.

## The standard a post must meet

**1. Every claim is backed.** A reader should be able to check any sentence. Why this matters: the site's whole premise is "I tried it and measured it". One unsupported claim makes the rest look like opinion. Acceptable backing:
- a link to a primary source (spec, JEP, vendor docs, release notes, the project's own repo) that you actually opened and that says what you claim;
- a named file in this repo: a log in `posts/evidence/`, a screenshot in `posts/images/`, a recording in `posts/media/`;
- a result the author supplied (output, screenshot, benchmark). Never estimate or invent a number. If a value was read by eye from a chart, say so and say it is approximate.

If a claim has no backing, either get backing (ask the author for the log or run, or fetch the source) or cut the claim. Opinions and lessons are fine when they are plainly labelled as the author's and tied to the evidence that taught them. Don't quote talks or videos you could not read or hear; cite the session page or primary source. Don't write "built" or "production" unless true; say "explored" or "tried".

**2. The post is in depth.** A results table alone is not a post. For each thing the post presents, explain the mechanism, not only the outcome: how it works, why it was set up that way, what happened, and why that happened. Define a term the first time a general reader would meet it. Show the setup next to the result so the numbers can be judged (versions, instance sizes, fixtures, how many runs). Include what went wrong and how it was fixed; those passages are usually where the reader learns the most. Prefer one section that fully explains an idea over three that mention it.

**3. The post says what it explains, why, and what the reader will understand.** Put this up front, so a reader can decide in ten seconds whether to read on and can tell afterwards whether it delivered. See the opening block in the structure below. Why this matters: posts that start from "here is my result" leave the reader guessing what they were meant to take away.

**4. Honest limits.** Say what was not proven and what would change the conclusion. A visible "Not proven" list builds more trust than a clean story. Compare like with like, and put differences in setup beside the result. Mark incubating, preview or prototype things as experimental.

## Workflow

1. **Gather what exists.** Ask the author for the POC's evidence if it is not already in the repo or conversation: logs, screenshots, benchmark output, repo link, versions. Read `posts/evidence/` for similar logs. Don't start drafting claims you can't back.
2. **Settle the three opening answers** (and ask the author if unclear): *What does this post explain? Why did the author do it (what question or problem)? What will a reader understand or be able to do afterwards?*
3. **Build a claim ledger** before writing prose. A short list in your working notes, one line per factual claim: `claim | source (URL or repo path) | status`. Every row needs a source. Rows with none are removed from the post or flagged to the author. Numbers in the ledger must trace to supplied evidence.
4. **Verify sources.** Open each external URL (WebFetch) and confirm it loads and supports the wording; fix wording that goes beyond the source. For repo links the post sends readers to, confirm the repo is public (a private repo returns 404 for readers); otherwise ask or drop the link. For logs, copy the relevant output into `posts/evidence/<name>.txt` rather than paraphrasing from memory.
5. **Draft `posts/<slug>.md`** using the structure below. Slug: lowercase, hyphenated, descriptive.
6. **Wire it up** (see "Files to change").
7. **Run the checks** below yourself first. They catch the easy problems cheaply.
8. **Review gate (required before any commit).** Spawn the `post-reviewer` agent (`.claude/agents/post-reviewer.md`) on the post. It returns `VERDICT: APPROVED` or `VERDICT: DECLINED` with reasons. Why a separate agent: the author of a draft is the worst judge of its unsupported claims, so a fresh reader that checks against `AGENTS.md` and this skill is the gate.
   - **DECLINED:** do not commit. Spawn the `post-fixer` agent with the reviewer's reasons, then run `post-reviewer` again on the result. Repeat, at most 3 rounds.
   - If the fixer lists anything under "Needs the author" (real proof only the author has), or the post is still declined after 3 rounds, stop and ask the author for what is missing. Never satisfy the reviewer by inventing proof.
   - **APPROVED:** commit with a clear message. Don't push; ask first.
9. Report to the author with the social post text, the review verdict (and any rounds it took).

## Post structure

Start from `templates/poc-post.md`, with the opening block and depth sections below. Keep the voice simple and humble (learn, implement, mentor): what was tried, measured and learned, no hype.

```
# Title: a plain question or statement

**TL;DR**
- 3 to 4 bullets: what was tried, the headline result (with numbers), the main lesson.

<One short paragraph: what the POC is, what was compared or tried, and the environment it ran in.>

## What this post explains, and why
- **What:** the topic and scope, in a sentence or two.
- **Why:** the question or problem that prompted it.
- **After reading you will understand:** 2 to 4 concrete takeaways (what the reader will know or be able to do).
- **Not covered:** what this post does not claim or test.

## 1. What I tried / Measured
Results table (every row links to its proof), how the numbers were obtained, caveats,
then the screenshots, logs or videos.

## 2..n. How it works / Why / What went wrong
Numbered sections that explain the mechanism and the reasoning behind each choice,
with links to code (public repos only) and to the sources for any outside fact.

## What I learned
Each lesson tied to the run, log or source that taught it.

## Not proven
What was not tested, and what would change the conclusion.

## Worth watching (experimental)   <- optional
Experimental or upcoming items, clearly marked, each with a source.
```

Calibrate length to the topic: the scanner post is long because it covers a whole system and needs a reading guide; a focused comparison can be shorter. What isn't optional is that each section earns its place by explaining something the reader needs.

Formatting notes from the existing posts: put images in `posts/images/` and reference them as `posts/images/<file>` (served from the site root); tables work well for results and for "to understand X, read Y" maps; keep ASCII diagrams in fenced code blocks; recorded demos are `<video controls preload="metadata" width="100%" src="posts/media/<file>.mp4"></video>` and the text should say what in them is live and what is a replay.

## Files to change for a new post

1. `posts/<slug>.md`, plus images in `posts/images/` and logs in `posts/evidence/`.
2. `post.html`: add `'<slug>': 'posts/<slug>.md'` to the `POSTS` map. Only change `<title>`/description if the page is meant for a single post (the page title currently serves the default post).
3. `index.html`: add a card for the post under the writing section, newest first (copy an existing `<a class="card">`: a `tag` span, an `<h3>`, a one-or-two sentence `<p>`). Keep the "Latest write-up" ordering.
4. **Link preview:** from the repo root run `java scripts/MakeShare.java <slug> "<title>" "<description>" "<tag line>"`. It writes `share/<slug>.html` (Open Graph and Twitter Card tags, then a redirect to the post) and `posts/images/og-<slug>.png` (a 1200x630 title card). Why: X and LinkedIn crawlers read raw HTML without running JavaScript, and `post.html` is one page for every post, so only a per-post static page can carry per-post tags. Use the post's own title; write a one-sentence description that states only what the post can back; open the PNG and check the title fits. Re-run it if the title or description changes.
5. The social post text goes in your final reply to the author, not in the repo.

## Social post (X and LinkedIn share the same text)

- Link: `https://dhawanrachakonda.github.io/share/<slug>.html`, the share page, so the card previews. Never the `post.html?p=` link. Mention that platforms cache previews, so check it in LinkedIn's Post Inspector or the X compose box before posting.
- At most 280 characters on X, counting the link as 23 and each emoji as 2. Include the headline numbers and a pointer such as "Proof 👇".
- 2 to 3 hashtags at most. Say which images to attach.

## Checks before reporting done

- The claim ledger has no row without a source, and every number traces to supplied evidence.
- `share/<slug>.html` and `posts/images/og-<slug>.png` exist and match the post.
- Every external URL and every repo link in the post loads; the page, markdown file, images, share page and card return 200 on `dhawanrachakonda.github.io` (after publishing) or exist on disk (before).
- The opening block answers what, why and what the reader will understand, and the post delivers each takeaway it lists.
- A "Not proven" section exists and is honest.
- No `[ ]` or `<...>` template placeholders remain (search for them).
- `post-reviewer` returned `VERDICT: APPROVED` on the current version of the post. A decline blocks the commit.
- Commit with a clear message. Do not push, make repos public or post anywhere until the author says so.

In the final reply, give the author: the files changed, the claims you cut or flagged for lack of proof, the social text, and which images to attach.
