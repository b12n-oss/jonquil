# Jonquil

Jonquil is a DrRacket-style Clojure IDE written in Jolt: a Definitions editor, an Interactions console, Run and a Stop that really stops. It ports the idea of [DrClojure](https://github.com/kimtg/DrClojure-Neo), lifting its pure code for the evaluation and result-rendering engine.

**Status: pre-alpha, no GUI yet**

## Commands

```bash
bb check                  # require every namespace, exit 0
bb test                   # all test namespaces; exits 1 on any failure or error
bb test jonquil.edit-test # one namespace (the TDD loop)
```
