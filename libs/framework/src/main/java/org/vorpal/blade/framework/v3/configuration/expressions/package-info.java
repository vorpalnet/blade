/// Boolean expression evaluation against the routing
/// [org.vorpal.blade.framework.v3.configuration.Context].
///
/// [org.vorpal.blade.framework.v3.configuration.expressions.Expression]
/// is a small recursive-descent parser + evaluator driving
/// [org.vorpal.blade.framework.v3.configuration.routing.ConditionalRouting]'s
/// clause selection and
/// [org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader]'s
/// `when` gating on [org.vorpal.blade.framework.v3.configuration.routing.Route].
///
/// ## Safety
///
/// The grammar only allows variable lookups, literal values,
/// comparisons, and boolean combinators. No method invocation, no
/// scripting, no bytecode loading, no file or network access.
/// Malicious config files can't execute arbitrary code; at worst a
/// bad expression produces a wrong routing decision for its own
/// calls.
///
/// ## Grammar
///
/// ```
/// expr       := or_expr
/// or_expr    := and_expr ( '||' and_expr )*
/// and_expr   := not_expr ( '&&' not_expr )*
/// not_expr   := '!' not_expr | primary
/// primary    := '(' expr ')' | comparison | atom
/// comparison := atom ( '==' | '!=' | '<' | '>' | '<=' | '>=' ) atom
/// atom       := variable | number | string | bareword | boolean
/// variable   := '${' name '}'
/// string     := "'" any-chars-except-quote "'"
/// bareword   := non-operator non-whitespace characters
/// boolean    := 'true' | 'false'
/// ```
///
/// ## Operator precedence (high to low)
///
/// 1. `!` (unary negation)
/// 2. `<`, `>`, `<=`, `>=` (ordered comparison)
/// 3. `==`, `!=` (equality)
/// 4. `&&` (logical AND; short-circuit)
/// 5. `||` (logical OR; short-circuit)
///
/// Parentheses override precedence.
///
/// ## Value types
///
/// - **Variable** — `${name}` resolves against the Context using the
///   same fallback chain as templates: SipSession → SipApplicationSession
///   → environment variable → system property. Unresolved resolves to
///   null (false in boolean context, empty string in comparisons).
/// - **Number** — integer or decimal literal. When both sides of a
///   comparison parse as numbers, numeric comparison is used.
/// - **Bare word** — an unquoted identifier (no whitespace, no operator
///   characters). Treated as a string literal.
/// - **Single-quoted string** — required when the value contains
///   whitespace; optional otherwise.
/// - **Boolean literal** — lowercase `true` or `false`, case-sensitive.
///
/// ## Boolean coercion
///
/// When a value appears in a boolean context (operand of `!`, `&&`,
/// `||`, or a bare atom):
///
/// - `true`, `1`, `yes` (case-insensitive) → `true`
/// - everything else (including null, empty string) → `false`
///
/// ## Examples
///
/// ```
/// ${score} > 80
/// ${action} == allow
/// ${action} == allow && ${score} >= 50
/// ${override} == true || ${emergencyMode} == true
/// !${blocked}
/// ${description} == 'not allowed'
/// (${customerTier} == premium && ${shift} == business) || ${override}
/// ${hour} >= 8 && ${hour} <= 17
/// ${apiKey} != ''
/// ```
///
/// See [org.vorpal.blade.framework.v3.configuration.expressions.Expression]
/// for the public API.
package org.vorpal.blade.framework.v3.configuration.expressions;
