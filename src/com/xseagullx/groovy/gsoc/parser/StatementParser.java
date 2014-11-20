package com.xseagullx.groovy.gsoc.parser;

import com.xseagullx.groovy.gsoc.GASTBuilder;
import com.xseagullx.groovy.gsoc.GroovyParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ClosureListExpression;
import org.codehaus.groovy.ast.expr.EmptyExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.BreakStatement;
import org.codehaus.groovy.ast.stmt.CaseStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ContinueStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.SwitchStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

interface StatementParseAction<T extends ParserRuleContext> {
	Statement parseStatement(T stmt);
}

public class StatementParser {
	public static Map<Class<? extends ParserRuleContext>, StatementParseAction> parseActions = new HashMap<Class<? extends ParserRuleContext>, StatementParseAction>();

	/**
	 * Dispatch node to parse action to a correct parse action.
	 */
	public static Statement parseStatement(ParserRuleContext ctx) {
		StatementParseAction parseAction = parseActions.get(ctx.getClass());
		if (parseAction == null)
			throw new RuntimeException("Unsupported statement type! $ctx.text");

		//noinspection unchecked
		return parseAction.parseStatement(ctx);
	}

	public static Statement parseStatement(GroovyParser.BlockStatementContext ctx) {
		BlockStatement statement = new BlockStatement();
		if (ctx == null)
			return statement;

		for (GroovyParser.StatementContext stmt : ctx.statement())
			statement.addStatement(parseStatement(stmt));

		return GASTBuilder.setupNodeLocation(statement, ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.ExpressionStatementContext ctx) {
		return GASTBuilder.setupNodeLocation(new ExpressionStatement(GASTBuilder.parseExpression(ctx.expression())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.IfStatementContext ctx) {
		Statement trueBranch = parse(ctx.statementBlock(0));
		Statement falseBranch = ctx.KW_ELSE() != null ? parse(ctx.statementBlock(1)) : EmptyStatement.INSTANCE;
		BooleanExpression expression = new BooleanExpression(GASTBuilder.parseExpression(ctx.expression()));
		return GASTBuilder.setupNodeLocation(new IfStatement(expression, trueBranch, falseBranch), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.WhileStatementContext ctx) {
		return GASTBuilder.setupNodeLocation(new WhileStatement(new BooleanExpression(GASTBuilder.parseExpression(ctx.expression())), parse(ctx.statementBlock())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.ClassicForStatementContext ctx) {
		ClosureListExpression expression = new ClosureListExpression();

		boolean captureNext = false;
		for (ParseTree c : ctx.children) {
			// FIXME terrible logic.
			boolean isSemicolon = false;
			if (c instanceof TerminalNode) {
				String text = ((TerminalNode)c).getSymbol().getText();
				isSemicolon = (";".equals(text) || "(".equals(text) || ")".equals(text));
			}

			if (captureNext && isSemicolon)
				expression.addExpression(EmptyExpression.INSTANCE);
			else if (captureNext && c instanceof GroovyParser.ExpressionContext)
				expression.addExpression(GASTBuilder.parseExpression((GroovyParser.ExpressionContext)c));
			captureNext = isSemicolon;
		}

		Parameter parameter = ForStatement.FOR_LOOP_DUMMY;
		return GASTBuilder.setupNodeLocation(new ForStatement(parameter, expression, parse(ctx.statementBlock())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.ForInStatementContext ctx) {
		Parameter parameter = new Parameter(GASTBuilder.parseTypeDeclaration(ctx.typeDeclaration()), ctx.IDENTIFIER().getText());
		parameter = GASTBuilder.setupNodeLocation(parameter, ctx.IDENTIFIER().getSymbol());

		return GASTBuilder.setupNodeLocation(new ForStatement(parameter, GASTBuilder.parseExpression(ctx.expression()), parse(ctx.statementBlock())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.ForColonStatementContext ctx) {
		if (ctx.typeDeclaration() == null)
			throw new RuntimeException("Classic for statement require type to be declared.");
		Parameter parameter = new Parameter(GASTBuilder.parseTypeDeclaration(ctx.typeDeclaration()), ctx.IDENTIFIER().getText());
		parameter = GASTBuilder.setupNodeLocation(parameter, ctx.IDENTIFIER().getSymbol());

		return GASTBuilder.setupNodeLocation(new ForStatement(parameter, GASTBuilder.parseExpression(ctx.expression()), parse(ctx.statementBlock())), ctx);
	}

	public static Statement parse(GroovyParser.StatementBlockContext ctx) {
		if (ctx.statement() != null)
			return GASTBuilder.setupNodeLocation(parseStatement(ctx.statement()), ctx.statement());
		else
			return parseStatement(ctx.blockStatement());
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.SwitchStatementContext ctx) {
		List<CaseStatement> caseStatements = new ArrayList<CaseStatement>();
		for (GroovyParser.CaseStatementContext caseStmt : ctx.caseStatement()) {
			BlockStatement stmt = new BlockStatement(); // #BSC
			for (GroovyParser.StatementContext st : caseStmt.statement())
				stmt.addStatement(parseStatement(st));

			caseStatements.add(GASTBuilder.setupNodeLocation(new CaseStatement(GASTBuilder.parseExpression(caseStmt.expression()), stmt),
				caseStmt.KW_CASE().getSymbol())); // There only 'case' kw was highlighted in parser old version.
		}

		Statement defaultStatement;
		if (ctx.KW_DEFAULT() != null) {
			BlockStatement blockStatement = new BlockStatement();
			for (GroovyParser.StatementContext stmt : ctx.statement())
				blockStatement.addStatement(parseStatement(stmt));
			defaultStatement = blockStatement; // #BSC
		}
		else
			defaultStatement = EmptyStatement.INSTANCE; // TODO Refactor empty stataements and expressions.

		return new SwitchStatement(GASTBuilder.parseExpression(ctx.expression()), caseStatements, defaultStatement);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.DeclarationStatementContext ctx) {
		return GASTBuilder.setupNodeLocation(new ExpressionStatement(GASTBuilder.parseDeclaration(ctx.declarationRule())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.NewArrayStatementContext ctx) {
		return GASTBuilder.setupNodeLocation(new ExpressionStatement(GASTBuilder.parse(ctx.newArrayRule())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.NewInstanceStatementContext ctx) {
		return GASTBuilder.setupNodeLocation(new ExpressionStatement(GASTBuilder.parse(ctx.newInstanceRule())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.ControlStatementContext ctx) {
		// TODO check validity. Labeling support.
		// Fake inspection result should be suppressed.
		//noinspection GroovyConditionalWithIdenticalBranches
		return GASTBuilder.setupNodeLocation(ctx.KW_BREAK() != null ? new BreakStatement() : new ContinueStatement(), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.ReturnStatementContext ctx) {
		GroovyParser.ExpressionContext expression = ctx.expression();
		Expression expr = expression != null ? GASTBuilder.parseExpression(expression) : EmptyExpression.INSTANCE;
		return GASTBuilder.setupNodeLocation(new ReturnStatement(expr), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.ThrowStatementContext ctx) {
		return GASTBuilder.setupNodeLocation(new ThrowStatement(GASTBuilder.parseExpression(ctx.expression())), ctx);
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.TryCatchFinallyStatementContext ctx) {
		Statement finallyStatement;

		if (ctx.finallyBlock() == null)
			finallyStatement = EmptyStatement.INSTANCE;
		else {
			GroovyParser.BlockStatementContext finallyBlockStatement = ctx.finallyBlock().blockStatement();
			BlockStatement fbs = new BlockStatement();
			fbs.addStatement(parseStatement(finallyBlockStatement));
			finallyStatement = GASTBuilder.setupNodeLocation(fbs, finallyBlockStatement);
		}

		TryCatchStatement statement = new TryCatchStatement(parseStatement(ctx.tryBlock().blockStatement()), finallyStatement);
		for (GroovyParser.CatchBlockContext cbc : ctx.catchBlock()) {
			Statement catchBlock = parseStatement(cbc.blockStatement());
			String var = cbc.IDENTIFIER().getText();

			List<GroovyParser.ClassNameExpressionContext> classNameExpression = cbc.classNameExpression();
			if (classNameExpression.isEmpty())
				statement.addCatch(GASTBuilder.setupNodeLocation(new CatchStatement(new Parameter(ClassHelper.OBJECT_TYPE, var), catchBlock), cbc));
			else {
				for (GroovyParser.ClassNameExpressionContext cne : classNameExpression)
					statement.addCatch(GASTBuilder.setupNodeLocation(new CatchStatement(new Parameter(GASTBuilder.parseExpression(cne), var), catchBlock), cbc));
			}
		}
		return statement;
	}

	@SuppressWarnings("GroovyUnusedDeclaration")
	public static Statement parseStatement(GroovyParser.CommandExpressionStatementContext ctx) {
		Expression expression = null;
		List<ParseTree> list = ctx.cmdExpressionRule().children;
		for (int i = 0; i < list.size(); i += 2) {
			ParseTree c1 = list.get(i);
			ParseTree c0 = (i + 1) < list.size() ? list.get(i + 1) : null;

			if (c0 == null)
				expression = new PropertyExpression(expression, c1.getText());
			else {
				Expression arguments = GASTBuilder.createArgumentList((GroovyParser.ArgumentListContext)c0);

				if (c1 instanceof TerminalNode) {
					expression = new MethodCallExpression(expression, c1.getText(), arguments);
					((MethodCallExpression)expression).setImplicitThis(false);
				}
				else if (c1 instanceof GroovyParser.PathExpressionContext) {
					//noinspection unchecked
					List<Object> res = (List<Object>)GASTBuilder.parsePathExpression((GroovyParser.PathExpressionContext)c1);
					expression = (Expression)res.get(0);
					String methodName = ((TerminalNode)res.get(1)).getText();
					boolean implicitThis = (Boolean)res.get(2);

					expression = new MethodCallExpression(expression, methodName, arguments);
					((MethodCallExpression)expression).setImplicitThis(implicitThis);
				}
			}
		}

		if (expression == null)
			throw new GroovyBugError("CommandExpressionStatementContext cannot be empty.");

		return new ExpressionStatement(expression);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// Generated code.
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Used to work like an 'multimethods'.
	 * Generated from IDEA Hierarchy view using foloving snippet.

	 .split('\n')*.split(' ').collect { it.first() }.collect {
	 """\t\tparseActions.put(GroovyParser.${it}.class, new StatementParseAction<GroovyParser.${it}>() {
	 \t\t\t@Override public Statement parseStatement(GroovyParser.${it} stmt) {
	 \t\t\t\treturn StatementParser.parseStatement(stmt);
	 \t\t\t}
	 \t\t});"""
	 }.join('\n')
	 */
	static {
		parseActions.put(GroovyParser.BlockStatementContext.class, new StatementParseAction<GroovyParser.BlockStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.BlockStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.ThrowStatementContext.class, new StatementParseAction<GroovyParser.ThrowStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.ThrowStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.NewInstanceStatementContext.class, new StatementParseAction<GroovyParser.NewInstanceStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.NewInstanceStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.CommandExpressionStatementContext.class, new StatementParseAction<GroovyParser.CommandExpressionStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.CommandExpressionStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.ClassicForStatementContext.class, new StatementParseAction<GroovyParser.ClassicForStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.ClassicForStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.IfStatementContext.class, new StatementParseAction<GroovyParser.IfStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.IfStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.WhileStatementContext.class, new StatementParseAction<GroovyParser.WhileStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.WhileStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.TryCatchFinallyStatementContext.class, new StatementParseAction<GroovyParser.TryCatchFinallyStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.TryCatchFinallyStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.ForColonStatementContext.class, new StatementParseAction<GroovyParser.ForColonStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.ForColonStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.ReturnStatementContext.class, new StatementParseAction<GroovyParser.ReturnStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.ReturnStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.ExpressionStatementContext.class, new StatementParseAction<GroovyParser.ExpressionStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.ExpressionStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.ForInStatementContext.class, new StatementParseAction<GroovyParser.ForInStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.ForInStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.DeclarationStatementContext.class, new StatementParseAction<GroovyParser.DeclarationStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.DeclarationStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.SwitchStatementContext.class, new StatementParseAction<GroovyParser.SwitchStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.SwitchStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.ControlStatementContext.class, new StatementParseAction<GroovyParser.ControlStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.ControlStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
		parseActions.put(GroovyParser.NewArrayStatementContext.class, new StatementParseAction<GroovyParser.NewArrayStatementContext>() {
			@Override public Statement parseStatement(GroovyParser.NewArrayStatementContext stmt) {
				return StatementParser.parseStatement(stmt);
			}
		});
	}
}
