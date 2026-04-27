package org.vorpal.blade.framework.v3.configuration.expressions;

import org.vorpal.blade.framework.v3.FakeContext;

/// Smoke-test driver for [Expression]. No JUnit dependency — prints
/// PASS/FAIL per check and exits non-zero on any failure so the
/// wrapper script can detect it.
///
/// Run:
/// ```
/// mvn -pl libs/framework -am test-compile
/// java -cp libs/framework/target/test-classes:libs/framework/target/classes \
///      org.vorpal.blade.framework.v3.configuration.expressions.ExpressionSmokeTest
/// ```
public final class ExpressionSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		// literals
		checkLiteralsAndBooleans();
		// variable resolution
		checkVariableResolution();
		// equality + inequality
		checkEqualityOps();
		// ordered comparisons
		checkOrderedComparisons();
		// numeric vs string coercion
		checkNumericCoercion();
		// logical combinators
		checkLogicalCombinators();
		// precedence + parens
		checkPrecedence();
		// unary not
		checkUnaryNot();
		// quoted strings with whitespace
		checkQuotedStrings();
		// bareword strings
		checkBareWords();
		// boolean atoms (non-comparison)
		checkBooleanAtoms();
		// null / missing variable behavior
		checkMissingVariables();
		// parse errors
		checkParseErrors();
		// realistic expressions
		checkRealisticExpressions();

		summary();
	}

	private static void checkLiteralsAndBooleans() {
		FakeContext ctx = new FakeContext();
		check("literal.true", new Expression("true").evaluate(ctx));
		check("literal.false", !new Expression("false").evaluate(ctx));
		check("literal.eq.true", new Expression("true == true").evaluate(ctx));
		check("literal.ne.true", !new Expression("true == false").evaluate(ctx));
	}

	private static void checkVariableResolution() {
		FakeContext ctx = new FakeContext().set("action", "allow").set("score", "85");
		check("var.equals", new Expression("${action} == allow").evaluate(ctx));
		check("var.notEquals", new Expression("${action} != block").evaluate(ctx));
		check("var.score.gt", new Expression("${score} > 80").evaluate(ctx));
		check("var.score.ge", new Expression("${score} >= 85").evaluate(ctx));
		check("var.missing", !new Expression("${missing} == foo").evaluate(ctx));
	}

	private static void checkEqualityOps() {
		FakeContext ctx = new FakeContext().set("a", "foo").set("b", "foo").set("c", "bar");
		check("eq.string.true", new Expression("${a} == ${b}").evaluate(ctx));
		check("eq.string.false", !new Expression("${a} == ${c}").evaluate(ctx));
		check("ne.string.true", new Expression("${a} != ${c}").evaluate(ctx));
		check("ne.string.false", !new Expression("${a} != ${b}").evaluate(ctx));
	}

	private static void checkOrderedComparisons() {
		FakeContext ctx = new FakeContext();
		check("num.lt", new Expression("5 < 10").evaluate(ctx));
		check("num.le.equal", new Expression("10 <= 10").evaluate(ctx));
		check("num.gt", new Expression("15 > 10").evaluate(ctx));
		check("num.ge.equal", new Expression("10 >= 10").evaluate(ctx));
		check("num.lt.false", !new Expression("15 < 10").evaluate(ctx));
	}

	private static void checkNumericCoercion() {
		FakeContext ctx = new FakeContext().set("score", "42").set("threshold", "50");
		check("coerce.both.numeric", new Expression("${score} < ${threshold}").evaluate(ctx));
		// When one side doesn't parse as a number, fall to string comparison
		FakeContext mixed = new FakeContext().set("name", "zebra");
		check("coerce.string.fallback.lt", new Expression("${name} > apple").evaluate(mixed));
		// Floating point
		check("coerce.float", new Expression("3.14 > 3").evaluate(ctx));
		check("coerce.negative", new Expression("-5 < 0").evaluate(ctx));
	}

	private static void checkLogicalCombinators() {
		FakeContext ctx = new FakeContext().set("a", "1").set("b", "2").set("c", "3");
		check("and.both.true", new Expression("${a} == 1 && ${b} == 2").evaluate(ctx));
		check("and.one.false", !new Expression("${a} == 1 && ${b} == 99").evaluate(ctx));
		check("or.first.true", new Expression("${a} == 1 || ${b} == 99").evaluate(ctx));
		check("or.second.true", new Expression("${a} == 99 || ${b} == 2").evaluate(ctx));
		check("or.both.false", !new Expression("${a} == 99 || ${b} == 98").evaluate(ctx));
	}

	private static void checkPrecedence() {
		FakeContext ctx = new FakeContext().set("a", "1").set("b", "2").set("c", "3");
		// AND binds tighter than OR
		check("prec.and.tighter",
				new Expression("${a} == 99 || ${b} == 2 && ${c} == 3").evaluate(ctx));
		check("prec.and.tighter.neg",
				!new Expression("${a} == 99 || ${b} == 2 && ${c} == 99").evaluate(ctx));
		// Parens override
		check("prec.parens",
				new Expression("(${a} == 99 || ${b} == 2) && ${c} == 3").evaluate(ctx));
		check("prec.parens.neg",
				!new Expression("(${a} == 99 || ${b} == 99) && ${c} == 3").evaluate(ctx));
	}

	private static void checkUnaryNot() {
		FakeContext ctx = new FakeContext().set("flag", "false").set("active", "true");
		check("not.false", new Expression("!${flag}").evaluate(ctx));
		check("not.true", !new Expression("!${active}").evaluate(ctx));
		check("not.comparison", new Expression("!(${flag} == true)").evaluate(ctx));
		check("not.double", new Expression("!!${active}").evaluate(ctx));
	}

	private static void checkQuotedStrings() {
		FakeContext ctx = new FakeContext().set("msg", "not allowed");
		check("quoted.match", new Expression("${msg} == 'not allowed'").evaluate(ctx));
		check("quoted.noMatch", !new Expression("${msg} == 'allowed'").evaluate(ctx));
		check("quoted.empty", new Expression("${missing} == ''").evaluate(ctx));
	}

	private static void checkBareWords() {
		FakeContext ctx = new FakeContext().set("action", "allow").set("tier", "premium");
		check("bare.allow", new Expression("${action} == allow").evaluate(ctx));
		check("bare.premium", new Expression("${tier} == premium").evaluate(ctx));
		check("bare.combined",
				new Expression("${action} == allow && ${tier} == premium").evaluate(ctx));
	}

	private static void checkBooleanAtoms() {
		FakeContext ctx = new FakeContext()
				.set("flag", "true").set("disabled", "false")
				.set("yes", "yes").set("one", "1").set("zero", "0")
				.set("empty", "");
		check("atom.true", new Expression("${flag}").evaluate(ctx));
		check("atom.false", !new Expression("${disabled}").evaluate(ctx));
		check("atom.yes", new Expression("${yes}").evaluate(ctx));
		check("atom.one", new Expression("${one}").evaluate(ctx));
		check("atom.zero", !new Expression("${zero}").evaluate(ctx));
		check("atom.empty", !new Expression("${empty}").evaluate(ctx));
	}

	private static void checkMissingVariables() {
		FakeContext ctx = new FakeContext();
		// Missing var in boolean context → false
		check("missing.bool", !new Expression("${nonexistent}").evaluate(ctx));
		// Missing var in comparison → empty-string comparison
		check("missing.eq.empty", new Expression("${nonexistent} == ''").evaluate(ctx));
		check("missing.ne.value", new Expression("${nonexistent} != foo").evaluate(ctx));
	}

	private static void checkParseErrors() {
		checkParseError("unbalanced.paren", "(${a} == 1");
		checkParseError("unterminated.var", "${a == 1");
		checkParseError("unterminated.string", "'unclosed");
		checkParseError("trailing.content", "${a} == 1 extra-stuff-here");
	}

	private static void checkRealisticExpressions() {
		FakeContext ctx = new FakeContext()
				.set("action", "allow")
				.set("shift", "business")
				.set("customerTier", "premium")
				.set("score", "90");

		check("real.allowed.business",
				new Expression("${action} == allow && ${shift} == business").evaluate(ctx));
		check("real.premium.gated",
				new Expression("${customerTier} == premium && ${score} >= 80").evaluate(ctx));
		check("real.nested",
				new Expression("(${action} == allow && ${shift} == business) "
						+ "|| ${customerTier} == premium").evaluate(ctx));
		check("real.negation",
				new Expression("${action} != block && !(${score} < 50)").evaluate(ctx));
	}

	// ---- harness ----

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}

	private static void checkParseError(String name, String source) {
		try {
			new Expression(source);
			failed++;
			System.out.println("FAIL  " + name + " (expected parse error, got success)");
		} catch (IllegalArgumentException expected) {
			passed++;
			System.out.println("PASS  " + name);
		} catch (Exception other) {
			failed++;
			System.out.println("FAIL  " + name + " (wrong exception: " + other.getClass().getSimpleName() + ")");
		}
	}

	private static void summary() {
		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) {
			System.exit(1);
		}
	}
}
