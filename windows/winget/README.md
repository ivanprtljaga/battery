# winget

`winget install allthingsclaude.Battery` needs three YAML files in Microsoft's
[`winget-pkgs`](https://github.com/microsoft/winget-pkgs) repository, not in this
one. What lives here is the version-independent part of them, so a submission is
a copy and two substitutions rather than a fresh authoring job every release.

## Submitting a release

`windows-release.yml` prints the installer's SHA256 and puts it in the release
body, so the number that ships and the number submitted are the same bytes:

```
sha256: 6E7A…
```

Then, from a clone of `winget-pkgs`:

```
manifests/a/allthingsclaude/Battery/<version>/
  allthingsclaude.Battery.yaml
  allthingsclaude.Battery.locale.en-US.yaml
  allthingsclaude.Battery.installer.yaml
```

Copy the three files beside this README, replacing `<VERSION>` and `<SHA256>`.
`winget validate` and `winget install --manifest` both take the directory, and
running them before opening the pull request is the difference between a review
that takes a day and one that takes a week.

## Why this is not automated

The usual answer is an action that opens the pull request for you. It is not
here yet for a reason worth stating: it needs a personal access token with
`public_repo` on a fork of `winget-pkgs`, stored as a repository secret, and
that is a credential this repository does not otherwise hold. A first release
should be submitted by hand anyway — the review comments on a package's first
appearance are about the package, not about the automation.

## The identifier

`allthingsclaude.Battery`, matching the GitHub owner rather than the app name
alone, because `Battery` on its own is what winget would reject as ambiguous:
there are already power-management tools by that name.
