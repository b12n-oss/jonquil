# Contributing to Jonquil

Thanks for wanting to help. The full guide is
[Contributing](../docs/guide/contributing.md) (also at
https://jonquil.b12n.app/guide/contributing.html). In short:

1. Read [Getting started](../docs/guide/getting-started.md) to build and run
   from source.
2. Branch from `develop`, and keep a change to one idea. `main` only moves
   at a release.
3. Run `jolt verify` before you push. It runs lint, the unit suite and the
   live nREPL suite. CI runs lint, the format check and the metrics gate on
   every pull request.
4. Open a pull request against `develop` (GitHub offers `main` by default)
   that says what changed and how you checked it.

Changes written with an LLM's help are welcome on the same terms as any
other, and [AI_POLICY.md](../AI_POLICY.md) says how that works: you own the
code you submit and answer for it.

Bug reports and ideas go in [issues](https://github.com/b12n-oss/jonquil/issues/new/choose).
Please report security problems privately, as [SECURITY.md](../SECURITY.md)
describes.
