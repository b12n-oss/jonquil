# Security

Please don't report a security problem in a public issue.

Report it privately through GitHub instead: on the repository's **Security**
tab, choose **Report a vulnerability**
(https://github.com/b12n-oss/jonquil/security/advisories/new). Only the
maintainers see the report, and we'll reply there.

Some places worth knowing about when you look:

- **The MCP server** lets an agent drive a window, over stdio by default.
  With `:mcp-listen? true` (off by default) a window also listens on a
  127.0.0.1 socket with no authentication, so any local process can run code
  as you, as with a local nREPL server. The guide's
  [MCP page](docs/guide/mcp.md) has the details.
- **The nREPL dialects** run whatever code they're sent. Jonquil never starts
  a server you attach to, so keep a remote one on a network you trust or reach
  it through an ssh tunnel.
- **Plugins** are Clojure code that runs inside Jonquil. Install only plugins
  you trust, as you would any other code.

Only the latest release gets fixes.
