# Contributing

If you would like to contribute code to this project you can do so through GitHub by forking the repository and sending
a pull request.

When submitting code, please make every effort to follow existing conventions and style in order to keep the code as
readable as possible. This project emphasizes accuracy and reliability in fee estimation, so please ensure your changes
maintain or improve these qualities.

Before your code can be accepted into the project you must also sign the
[Individual Contributor License Agreement (CLA)][1].

## Building

This project uses CashApp's [Hermit](https://cashapp.github.io/hermit/). Hermit ensures that your team, your contributors,
and your CI have the same consistent tooling. Here are the [installation instructions](https://cashapp.github.io/hermit/usage/get-started/#installing-hermit).

[Activate Hermit](https://cashapp.github.io/hermit/usage/get-started/#activating-an-environment) either
by [enabling the shell hooks](https://cashapp.github.io/hermit/usage/shell/) (one-time only, recommended) or manually
sourcing the env with `. ./bin/activate-hermit`.

Use gradle to run all tests:

```shell
gradle build
```

[1]: https://spreadsheets.google.com/spreadsheet/viewform?formkey=dDViT2xzUHAwRkI3X3k5Z0lQM091OGc6MQ&ndplr=1

[2]: https://github.com/Kotlin/binary-compatibility-validator 