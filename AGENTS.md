# How to publish a POC post on this site

This is a personal site (GitHub Pages, static HTML, no build step). Each proof of concept (POC) gets one write-up plus a short social post. Follow this format every time. The reference example is `posts/java-genai-proof.md`; start from `templates/poc-post.md`.

## Voice

Simple and humble: learn, implement, mentor. Say what was tried, what was measured and what was learned. No hype, no "myth busting" unless the evidence supports it, and admit limits and caveats.

## Rules (non-negotiable)

1. **Every claim has a source**: a link, or a named file, chart or log in this repo. If there is no proof, leave the claim out.
2. **Numbers come only from evidence the author supplied** (screenshots, logs, benchmark output). Never estimate or invent a figure. If a number is read by eye from a chart, say so and say it is approximate.
3. **Compare like with like.** State differences in setup (JDK versions, instance sizes) next to the result.
4. **Mark experimental things as experimental** (incubating APIs, prototype builds).
5. **Don't quote talks or videos you could not read or hear.** Cite the session page or primary source instead.
6. **Don't overclaim authorship or experience.** Say "explored" or "tried", not "built" or "production", unless true.
7. **Check that repo links work.** Links to a private repo return 404 for readers, so confirm with the author that it's public, or leave the link out.
8. **Ask before pushing, making repos public, or posting anywhere.**

## Post structure (`posts/<slug>.md`)

1. `# Title`, written as a plain question or statement.
2. `**TL;DR**`: 3 to 4 bullets, with the headline result.
3. One short paragraph: what the POC is and what was compared or tried.
4. `## 1. Measured` (or `## 1. What I tried`): a results table, then a note on how the numbers were read and any caveats, then the screenshots or logs.
5. Numbered sections for setup or how it works, the key lesson, and anything related. Keep each short.
6. `## Worth watching` (optional): experimental or upcoming items, clearly marked.

Put images in `posts/images/` and reference them as `posts/images/<file>` (the page is served from the site root).

## Files to change for a new post

1. `posts/<slug>.md` and any images in `posts/images/`.
2. `post.html`: add `'<slug>': 'posts/<slug>.md'` to the `POSTS` map. Update `<title>` and the description if the page is for a single post.
3. `index.html`: add a card for the new post under the writing section (newest first).
4. Social post text (see below). Keep it in the final reply to the author, not in this repo.

## Social post (X and LinkedIn share the same text)

- Link to `https://dhawanrachakonda.github.io/post.html?p=<slug>`.
- Up to 280 characters on X, counting the link as 23 and an emoji as 2. Put the headline numbers and a pointer to "Proof 👇".
- 2 to 3 hashtags at most. Say which images to attach.

## Checks before reporting done

- Every URL for the page, markdown file and images returns 200 on `dhawanrachakonda.github.io`.
- No `[ ]` placeholders remain.
- Commit with a clear message. Push only after the author says so.
