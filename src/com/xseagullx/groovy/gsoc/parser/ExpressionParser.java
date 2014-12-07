package com.xseagullx.groovy.gsoc.parser;

import com.xseagullx.groovy.gsoc.GASTBuilder;
import com.xseagullx.groovy.gsoc.GroovyParser;
import com.xseagullx.groovy.gsoc.util.StringUtil;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.syntax.Numbers;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

interface ExpressionParserAction<T extends ParserRuleContext> {
	Expression parseExpression(T stmt);
}

public class ExpressionParser {
	public static Map<Class<? extends ParserRuleContext>, ExpressionParserAction> parseActions = new HashMap<Class<? extends ParserRuleContext>, ExpressionParserAction>();

	/**
	 * Dispatch node to parse action to a correct parse action.
	 */
	public static Expression parseExpression(ParserRuleContext ctx) {
		ExpressionParserAction parseAction = parseActions.get(ctx.getClass());
		if (parseAction == null)
			throw new RuntimeException("Unsupported expression type! $ctx.text");

		//noinspection unchecked
		return parseAction.parseExpression(ctx);
	}

	public static Expression parseExpression(GroovyParser.NewArrayExpressionContext ctx) {
		return GASTBuilder.parse(ctx.newArrayRule());
	}

	public static Expression parseExpression(GroovyParser.NewInstanceExpressionContext ctx) {
		return GASTBuilder.parse(ctx.newInstanceRule());
	}

	public static Expression parseExpression(GroovyParser.ParenthesisExpressionContext ctx) {
		return parseExpression(ctx.expression());
	}
	
	public static Expression parseExpression(GroovyParser.ListConstructorContext ctx) {
		List<GroovyParser.ExpressionContext> expressionContextList = ctx.expression();
		ArrayList<Expression> list = new ArrayList<Expression>(expressionContextList.size());
		for (GroovyParser.ExpressionContext context : expressionContextList)
			list.add(parseExpression(context));

		return GASTBuilder.setupNodeLocation(new ListExpression(list), ctx);
	}

	public static Expression parseExpression(GroovyParser.MapConstructorContext ctx) {
		List<GroovyParser.MapEntryContext> mapEntryContexts = ctx.mapEntry();
		List<MapEntryExpression> entryList = new ArrayList<MapEntryExpression>();
		if (mapEntryContexts != null) {
			for (GroovyParser.MapEntryContext context : mapEntryContexts)
				entryList.add(parseExpression(context));
		}

		return GASTBuilder.setupNodeLocation(new MapExpression(entryList), ctx);
	}

	public static MapEntryExpression parseExpression(GroovyParser.MapEntryContext ctx) {
		Expression keyExpr, valueExpr;
		List<GroovyParser.ExpressionContext> expressions = ctx.expression();
		if (expressions.size() == 1) {
			keyExpr = ctx.gstring() != null ?
				parseExpression(ctx.gstring()) :
				new ConstantExpression(ctx.IDENTIFIER() != null ?
					ctx.IDENTIFIER().getText() :
					GASTBuilder.parseString(ctx.STRING()));

			valueExpr = parseExpression(expressions.get(0));
		}
		else {
			keyExpr = parseExpression(expressions.get(0));
			valueExpr = parseExpression(expressions.get(1));
		}
		return GASTBuilder.setupNodeLocation(new MapEntryExpression(keyExpr, valueExpr), ctx);
	}

	public static Expression parseExpression(GroovyParser.ClosureExpressionContext ctx) {
		return parseExpression(ctx.closureExpressionRule());
	}

	public static Expression parseExpression(GroovyParser.ClosureExpressionRuleContext ctx) {
		Parameter[] parameters;
		if (ctx.argumentDeclarationList() == null)
			parameters = new Parameter[0];
		else {
			Parameter[] parsed = GASTBuilder.parseParameters(ctx.argumentDeclarationList());
			parameters = parsed == null || parsed.length == 0 ? null : parsed;
		}

		Statement statement = StatementParser.parseStatement(ctx.blockStatement());
		return GASTBuilder.setupNodeLocation(new ClosureExpression(parameters, statement), ctx);
	}

	public static Expression parseExpression(GroovyParser.BinaryExpressionContext ctx) {
		TerminalNode c = (TerminalNode)ctx.getChild(1);

		// Handle >, >> and >>>
		int i = 1;
		for (ParseTree next = ctx.getChild(i + 1); next instanceof TerminalNode && ((TerminalNode)next).getSymbol().getType() == GroovyParser.GT; next = ctx.getChild(i + 1))
			i++;

		Token op = GASTBuilder.createToken(c, i);
		Expression expression;
		Expression left = parseExpression(ctx.expression(0));
		Expression right = null; // Will be initialized later, in switch. We should handle as and instanceof creating
		// ClassExpression for given IDENTIFIERS. So, switch should fall through.
		switch (op.getType()) {
		case Types.RANGE_OPERATOR:
			right = parseExpression(ctx.expression(1));
			expression = new RangeExpression(left, right, !op.getText().endsWith("<"));
			break;
		case Types.KEYWORD_AS: {
			ClassNode classNode = GASTBuilder.setupNodeLocation(parseExpression(ctx.genericClassNameExpression()), ctx.genericClassNameExpression());
			expression = CastExpression.asExpression(classNode, left);
			break;
		}
		case Types.KEYWORD_INSTANCEOF: {
			ClassNode classNode = GASTBuilder.setupNodeLocation(parseExpression(ctx.genericClassNameExpression()), ctx.genericClassNameExpression());
			right = new ClassExpression(classNode);
		}
		default:
			if (right == null)
				right = parseExpression(ctx.expression(1));
			expression = new BinaryExpression(left, op, right);
			break;
		}

		expression.setColumnNumber(op.getStartColumn());
		expression.setLastColumnNumber(op.getStartColumn() + op.getText().length());
		expression.setLineNumber(op.getStartLine());
		expression.setLastLineNumber(op.getStartLine());
		return expression;
	}

	static Expression parseExpression(GroovyParser.TernaryExpressionContext ctx) {
		BooleanExpression boolExpr = new BooleanExpression(parseExpression(ctx.expression(0)));
		Expression trueExpr = parseExpression(ctx.expression(1));
		Expression falseExpr = parseExpression(ctx.expression(2));
		return GASTBuilder.setupNodeLocation(new TernaryExpression(boolExpr, trueExpr, falseExpr), ctx);
	}

	static Expression parseExpression(GroovyParser.ElvisExpressionContext ctx) {
		Expression baseExpr = parseExpression(ctx.expression(0));
		Expression falseExpr = parseExpression(ctx.expression(1));
		return GASTBuilder.setupNodeLocation(new ElvisOperatorExpression(baseExpr, falseExpr), ctx);
	}

	static Expression parseExpression(GroovyParser.UnaryExpressionContext ctx) {
		Expression node;
		TerminalNode op = (TerminalNode)ctx.getChild(0);
		String s = op.getText();
		if ("-".equals(s)) node = new UnaryMinusExpression(parseExpression(ctx.expression()));
		else if ("+".equals(s))
			node = new UnaryPlusExpression(parseExpression(ctx.expression()));
		else if ("!".equals(s))
			node = new NotExpression(parseExpression(ctx.expression()));
		else if ("~".equals(s))
			node = new BitwiseNegationExpression(parseExpression(ctx.expression()));
		else
			throw new AssertionError("There is no " + op.getText() + " handler.");

		node.setColumnNumber(op.getSymbol().getCharPositionInLine() + 1);
		node.setLineNumber(op.getSymbol().getLine());
		node.setLastLineNumber(op.getSymbol().getLine());
		node.setLastColumnNumber(op.getSymbol().getCharPositionInLine() + 1 + op.getText().length());
		return node;
	}

	static Expression parseExpression(GroovyParser.AnnotationParameterContext ctx) {
		if (ctx instanceof GroovyParser.AnnotationParamArrayExpressionContext) {
			GroovyParser.AnnotationParamArrayExpressionContext c = (GroovyParser.AnnotationParamArrayExpressionContext)ctx;
			List<GroovyParser.AnnotationParameterContext> contexts = c.annotationParameter();
			List<Expression> expressions = new ArrayList<Expression>(contexts.size());
			for (GroovyParser.AnnotationParameterContext context : contexts)
				expressions.add(parseExpression(context));

			return GASTBuilder.setupNodeLocation(new ListExpression(expressions), c);
		}
		else if (ctx instanceof GroovyParser.AnnotationParamBoolExpressionContext)
			return parseExpression((GroovyParser.AnnotationParamBoolExpressionContext)ctx);
		else if (ctx instanceof GroovyParser.AnnotationParamClassExpressionContext)
			return GASTBuilder.setupNodeLocation(new ClassExpression(parseExpression(((GroovyParser.AnnotationParamClassExpressionContext)ctx).genericClassNameExpression())), ctx);
		else if (ctx instanceof GroovyParser.AnnotationParamDecimalExpressionContext)
			return parseExpression((GroovyParser.AnnotationParamDecimalExpressionContext)ctx);
		else if (ctx instanceof GroovyParser.AnnotationParamIntegerExpressionContext)
			return parseExpression((GroovyParser.AnnotationParamIntegerExpressionContext)ctx);
		else if (ctx instanceof GroovyParser.AnnotationParamNullExpressionContext)
			return parseExpression((GroovyParser.AnnotationParamNullExpressionContext)ctx);
		else if (ctx instanceof GroovyParser.AnnotationParamPathExpressionContext)
			return collectPathExpression(((GroovyParser.AnnotationParamPathExpressionContext)ctx).pathExpression());
		else if (ctx instanceof GroovyParser.AnnotationParamStringExpressionContext)
			return parseExpression((GroovyParser.AnnotationParamStringExpressionContext)ctx);
		else
			throw new CompilationFailedException(CompilePhase.PARSING.getPhaseNumber(), GASTBuilder.getInstance().getSourceUnit(), new IllegalStateException(ctx + " is prohibited inside annotations."));
	}

	public static VariableExpression parseExpression(GroovyParser.VariableExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(new VariableExpression(ctx.IDENTIFIER().getText()), ctx);
	}

	public static PropertyExpression parseExpression(GroovyParser.FieldAccessExpressionContext ctx) {
		TerminalNode op = (TerminalNode)ctx.getChild(1);
		String text = ctx.IDENTIFIER().getText();
		Expression left = parseExpression(ctx.expression());
		Expression right = new ConstantExpression(text);
		PropertyExpression node;
		if (".@".equals(op.getText()))
			node = new AttributeExpression(left, right);
		else {
			String text1 = ctx.getChild(1).getText();
			node = new PropertyExpression(left, right, "?.".equals(text1) || "*.".equals(text1));
		}
		GASTBuilder.setupNodeLocation(node, ctx);
		node.setSpreadSafe("*.".equals(op.getText()));
		return node;
	}

	public static PrefixExpression parseExpression(GroovyParser.PrefixExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(new PrefixExpression(GASTBuilder.createToken((TerminalNode)ctx.getChild(0)), parseExpression(ctx.expression())), ctx);
	}

	static PostfixExpression parseExpression(GroovyParser.PostfixExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(new PostfixExpression(parseExpression(ctx.expression()), GASTBuilder.createToken((TerminalNode)ctx.getChild(1))), ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.AnnotationParamDecimalExpressionContext ctx) {
		return parseDecimal(ctx.DECIMAL().getText(), ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.ConstantDecimalExpressionContext ctx) {
		return parseDecimal(ctx.DECIMAL().getText(), ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.ConstantIntegerExpressionContext ctx) {
		return parseInteger(ctx.INTEGER().getText(), ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.AnnotationParamIntegerExpressionContext ctx) {
		return parseInteger(ctx.INTEGER().getText(), ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.BoolExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(new ConstantExpression(ctx.KW_FALSE() == null, true), ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.AnnotationParamBoolExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(new ConstantExpression(ctx.KW_FALSE() == null, true), ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.ConstantExpressionContext ctx) {
		return parseConstantString(ctx);
	}

	static ConstantExpression parseExpression(GroovyParser.AnnotationParamStringExpressionContext ctx) {
		return parseConstantString(ctx);
	}

	static Expression parseExpression(GroovyParser.GstringExpressionContext ctx) {
		return parseExpression(ctx.gstring());
	}

	static Expression parseExpression(GroovyParser.GstringContext ctx) {
		List<ConstantExpression> strings = new ArrayList<ConstantExpression>();
		strings.add(new ConstantExpression(clearStart(ctx.GSTRING_START().getText())));
		for (TerminalNode node : ctx.GSTRING_PART())
			strings.add(new ConstantExpression(clearPartOrEnd(node.getText())));
		strings.add(new ConstantExpression(clearPartOrEnd(ctx.GSTRING_END().getText())));

		List<Expression> expressions = new ArrayList<Expression>();

		List<ParseTree> children = ctx.children;
		for (int i = 0; i < children.size(); i++)
		{
			ParseTree it = children.get(i);
			if (it instanceof GroovyParser.ExpressionContext) {
				// We can guarantee, that it will be at least fallback ExpressionContext multimethod overloading, that can handle such situation.
				expressions.add(parseExpression((GroovyParser.ExpressionContext)it));
			}
			else if (it instanceof GroovyParser.GstringPathExpressionContext)
				expressions.add(collectPathExpression((GroovyParser.GstringPathExpressionContext)it));
			else if (it instanceof TerminalNode) {
				ParseTree next = i + 1 < children.size() ? children.get(i + 1) : null;
				if (next instanceof TerminalNode && ((TerminalNode)next).getSymbol().getType() == GroovyParser.RCURVE)
				expressions.add(new ConstantExpression(null));
			}
		}

		GStringExpression gstringNode = new GStringExpression(ctx.getText(), strings, expressions);
		return GASTBuilder.setupNodeLocation(gstringNode, ctx);
	}

	static Expression parseExpression(GroovyParser.NullExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(new ConstantExpression(null), ctx);
	}

	static Expression parseExpression(GroovyParser.AnnotationParamNullExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(new ConstantExpression(null), ctx);
	}

	static Expression parseExpression(GroovyParser.AssignmentExpressionContext ctx) {
		Expression left = parseExpression(ctx.expression(0)); // TODO reference to AntlrParserPlugin line 2304 for error handling.
		Expression right = parseExpression(ctx.expression(1));
		return GASTBuilder.setupNodeLocation(new BinaryExpression(left, GASTBuilder.createToken((TerminalNode)ctx.getChild(1)), right), ctx);
	}

	static Expression parseExpression(GroovyParser.DeclarationExpressionContext ctx) {
		return GASTBuilder.parseDeclaration(ctx.declarationRule());
	}

	static Expression parseExpression(GroovyParser.CallExpressionContext ctx) {
		MethodCallExpression methodNode;
		//FIXME in log a, b; a is treated as path expression and became a method call instead of variable
		if (ctx.LPAREN() == null && ctx.closureExpressionRule().size() == 0)
			return collectPathExpression(ctx.pathExpression());

		// Collect closure's in argumentList expression.
		TupleExpression argumentListExpression = GASTBuilder.createArgumentList(ctx.argumentList());
		for (GroovyParser.ClosureExpressionRuleContext context : ctx.closureExpressionRule())
			argumentListExpression.addExpression(parseExpression(context));
		
		List<Object> pathExpression = parsePathExpression(ctx.pathExpression());
		Expression expression = (Expression)pathExpression.get(0);
		String methodName = ((TerminalNode)pathExpression.get(1)).getText();
		boolean implicitThis = (Boolean)pathExpression.get(2);
		methodNode = new MethodCallExpression(expression, methodName, argumentListExpression);
		methodNode.setImplicitThis(implicitThis);
		return methodNode;
	}

	static MethodCallExpression parseExpression(GroovyParser.MethodCallExpressionContext ctx) {
		Expression method = new ConstantExpression(ctx.IDENTIFIER().getText());
		Expression argumentListExpression = GASTBuilder.createArgumentList(ctx.argumentList());
		MethodCallExpression expression = new MethodCallExpression(parseExpression(ctx.expression()), method, argumentListExpression);
		expression.setImplicitThis(false);
		TerminalNode op = (TerminalNode)ctx.getChild(1);
		expression.setSpreadSafe("*.".equals(op.getText()));
		expression.setSafe("?.".equals(op.getText()));
		return expression;
	}

	static ClassNode parseExpression(GroovyParser.ClassNameExpressionContext ctx) {
		return GASTBuilder.setupNodeLocation(ClassHelper.make(StringUtil.join(ctx.IDENTIFIER(), ".")), ctx);
	}

	static ClassNode parseExpression(GroovyParser.GenericClassNameExpressionContext ctx) {
		ClassNode classNode = parseExpression(ctx.classNameExpression());

		if (ctx.LBRACK() != null)
			classNode = classNode.makeArray();
		classNode.setGenericsTypes(GASTBuilder.parseGenericList(ctx.genericList()));
		return GASTBuilder.setupNodeLocation(classNode, ctx);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// Utility methods.
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	static ConstantExpression parseDecimal(String text, ParserRuleContext ctx) {
		return GASTBuilder.setupNodeLocation(new ConstantExpression(Numbers.parseDecimal(text), !text.startsWith("-")), ctx);
	}

	static ConstantExpression parseConstantString(ParserRuleContext ctx) {
		String text = ctx.getText();
		boolean isSlashy = text.startsWith("/");

		//Remove start and end quotes.
		if (text.startsWith("'''") || text.startsWith("\"\"\""))
			text = text.length() == 6 ? "" : text.substring(3, text.length() - 3);
		else if (text.startsWith("'") || text.startsWith("/") || text.startsWith("\""))
			text = text.length() == 2 ? "" : text.substring(1, text.length() - 1);

		//Find escapes.
		if (!isSlashy)
			text = StringUtil.replaceStandardEscapes(StringUtil.replaceOctalEscapes(text));
		else
			text = text.replace("\\/", "/");

		return GASTBuilder.setupNodeLocation(new ConstantExpression(text, true), ctx);
	}

	static ConstantExpression parseInteger(String text, ParserRuleContext ctx) {
		return GASTBuilder.setupNodeLocation(new ConstantExpression(Numbers.parseInteger(text), !text.startsWith("-")), ctx); //Why 10 is int but -10 is Integer?
	}

	static ConstantExpression parseInteger(String text, org.antlr.v4.runtime.Token ctx) {
		return GASTBuilder.setupNodeLocation(new ConstantExpression(Numbers.parseInteger(text), !text.startsWith("-")), ctx); //Why 10 is int but -10 is Integer?
	}

	/**
	 * Parse path expression.
	 * @param ctx
	 * @return tuple of 3 values: Expression, String methodName and boolean implicitThis flag.
	 */
	public static List<Object> parsePathExpression(GroovyParser.PathExpressionContext ctx) {
		Expression expression;
		List<TerminalNode> identifiers = ctx.IDENTIFIER();
		switch (identifiers.size()) {
		case 1: expression = VariableExpression.THIS_EXPRESSION; break;
		case 2: expression = new VariableExpression(identifiers.get(0).getText()); break;
		default:
			expression = new VariableExpression(identifiers.get(0).getText());
			for (int i = 1; i < identifiers.size() - 1; i++) // Iterate from second element to one before last(as last is methodName)
				expression = new PropertyExpression(expression, identifiers.get(i).getText());
			break;
		}
		return Arrays.asList(expression, identifiers.get(identifiers.size() - 1), identifiers.size() == 1);
	}

	static Expression collectPathExpression(GroovyParser.PathExpressionContext ctx) {
		List<TerminalNode> identifiers = ctx.IDENTIFIER();
		Expression expression = new VariableExpression(identifiers.get(0).getText());
		for (int i = 1; i < identifiers.size(); i++)
			expression = new PropertyExpression(expression, new ConstantExpression(identifiers.get(i).getText()));
		return expression;
	}

	static Expression collectPathExpression(GroovyParser.GstringPathExpressionContext ctx) {
		Expression expression = new VariableExpression(ctx.IDENTIFIER().getText());
		if (ctx.GSTRING_PATH_PART() != null) {
			for (TerminalNode node : ctx.GSTRING_PATH_PART())
				expression = new PropertyExpression(expression, new ConstantExpression(node.getText().substring(1)));
		}
		return expression;
	}

	private static String clearStart(String it) {
		return it.length() == 2 ? "" : it.substring(1, it.length() - 1);
	}

	private static String clearPartOrEnd(String it) {
		return it.length() == 1 ? "" : it.substring(0, it.length() - 1);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	// Generated code.
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Used to work like an 'multimethods'.
	 * Generated from IDEA Hierarchy view using foloving snippet.

	 .split('\n')*.split(' ').collect { it.first() }.collect {
	 """\t\tparseActions.put(GroovyParser.${it}.class, new ExpressionParserAction<GroovyParser.${it}>() {
	 \t\t\t@Override public Expression parseExpression(GroovyParser.${it} expr) {
	 \t\t\t\treturn ExpressionParser.parseExpression(expr);
	 \t\t\t}
	 \t\t});"""
	 }.join('\n')
	 */
	static
	{
		parseActions.put(GroovyParser.MethodCallExpressionContext.class, new ExpressionParserAction<GroovyParser.MethodCallExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.MethodCallExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.NullExpressionContext.class, new ExpressionParserAction<GroovyParser.NullExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.NullExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.TernaryExpressionContext.class, new ExpressionParserAction<GroovyParser.TernaryExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.TernaryExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.NewInstanceExpressionContext.class, new ExpressionParserAction<GroovyParser.NewInstanceExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.NewInstanceExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.ConstantExpressionContext.class, new ExpressionParserAction<GroovyParser.ConstantExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.ConstantExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.UnaryExpressionContext.class, new ExpressionParserAction<GroovyParser.UnaryExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.UnaryExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.FieldAccessExpressionContext.class, new ExpressionParserAction<GroovyParser.FieldAccessExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.FieldAccessExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.MapConstructorContext.class, new ExpressionParserAction<GroovyParser.MapConstructorContext>()
		{
			@Override public Expression parseExpression(GroovyParser.MapConstructorContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.DeclarationExpressionContext.class, new ExpressionParserAction<GroovyParser.DeclarationExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.DeclarationExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.PrefixExpressionContext.class, new ExpressionParserAction<GroovyParser.PrefixExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.PrefixExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.PostfixExpressionContext.class, new ExpressionParserAction<GroovyParser.PostfixExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.PostfixExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.GstringExpressionContext.class, new ExpressionParserAction<GroovyParser.GstringExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.GstringExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.AssignmentExpressionContext.class, new ExpressionParserAction<GroovyParser.AssignmentExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.AssignmentExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.CallExpressionContext.class, new ExpressionParserAction<GroovyParser.CallExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.CallExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.BinaryExpressionContext.class, new ExpressionParserAction<GroovyParser.BinaryExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.BinaryExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.ConstantIntegerExpressionContext.class, new ExpressionParserAction<GroovyParser.ConstantIntegerExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.ConstantIntegerExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.ClosureExpressionContext.class, new ExpressionParserAction<GroovyParser.ClosureExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.ClosureExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.ConstantDecimalExpressionContext.class, new ExpressionParserAction<GroovyParser.ConstantDecimalExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.ConstantDecimalExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.BoolExpressionContext.class, new ExpressionParserAction<GroovyParser.BoolExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.BoolExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.ParenthesisExpressionContext.class, new ExpressionParserAction<GroovyParser.ParenthesisExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.ParenthesisExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.ElvisExpressionContext.class, new ExpressionParserAction<GroovyParser.ElvisExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.ElvisExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.ListConstructorContext.class, new ExpressionParserAction<GroovyParser.ListConstructorContext>()
		{
			@Override public Expression parseExpression(GroovyParser.ListConstructorContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.NewArrayExpressionContext.class, new ExpressionParserAction<GroovyParser.NewArrayExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.NewArrayExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
		parseActions.put(GroovyParser.VariableExpressionContext.class, new ExpressionParserAction<GroovyParser.VariableExpressionContext>()
		{
			@Override public Expression parseExpression(GroovyParser.VariableExpressionContext expr)
			{
				return ExpressionParser.parseExpression(expr);
			}
		});
	}
}
