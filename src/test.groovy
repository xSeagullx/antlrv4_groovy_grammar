import com.xseagullx.groovy.gsoc.GroovyLexer
import com.xseagullx.groovy.gsoc.GroovyParser
import groovy.json.StringEscapeUtils
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.NotNull
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode

/*
println("Hello ${}__  ${} asd")
42 "It's a stri\"ng${ 1 + "Hello" }d" "String too" Identifier$ 'qu\'ot\123ed! $tring' + /quoted string with unescaped \ character / + /${ slashyGString + 1 } /
if (/hello/) { /hello/ + "${"Hello" + /${ slashyGString + 1 }/ }"}
( ordinary / division / d )
( / slashy sting / + d )
( d + / slashy sting / )
( d + " g ${ 42 } sting " )
( d + / g ${ 42 } sting / )
( d + / g ${ 42 } ${ 42 } sting / )
println(/Hello${asdd} $ sd /)
*/

def text =
'''
a(a: 5 )
'''

println("=" * 60)
println(text)
println("=" * 60)

def modes = [:]
def lexer = new GroovyLexer(new ANTLRInputStream(text)) {
    int modeLevel = 0
    @Override void emit(Token token) {
        modes[token] = modeNames[_mode]
        super.emit(token)
    }

    @Override void pushMode(int m) {
        modeLevel++
        super.pushMode(m)
        println("  " * modeLevel + "> ${modeNames[m]}")
    }

    @Override int popMode() {
        def m = super.popMode()
        modeLevel--
        println("  " * modeLevel + "< ${modeNames[m]}")
        return m
    }
}

def lines = lexer.allTokens.groupBy { it.line }
println("\nLexer TOKENS:\n")
def lastMode = null
println("\t" + lines.values().flatten().collect { it ->
    def mode = lastMode == modes[it] ? '...' : modes[it]
    lastMode = modes[it]
    "$it.line:$it.charPositionInLine [$mode] $it.type ${ GroovyLexer.tokenNames[it.type] } ${StringEscapeUtils.escapeJava(it.text)}" }.join('\n\t'))
println("=" * 60)
for (l in lines) {
    ArrayList<String> tokenDescriptions = l.value.collect { "${ GroovyLexer.tokenNames[it.type] } $it.charPositionInLine" }
    ArrayList<String> values = l.value.collect { StringEscapeUtils.escapeJava(it.text) }

    println(l.key.toString().padLeft(5) + ' |  ' + values.join(' '))
    for (int i in 0..<tokenDescriptions.size()) {
        int max = Math.max(tokenDescriptions[i].length(), values[i].length())
        tokenDescriptions[i] = tokenDescriptions[i].padRight(max)
        values[i] = values[i].padRight(max)
    }

    println(' ' * 6 + '|  ' + values.join(' '));
    println(' ' * 6 + '|  ' + tokenDescriptions.join(' ') + '\n')
}

println("=" * 60)

//return; //FIXME Enable parser

lexer = new GroovyLexer(new ANTLRInputStream(text))
CommonTokenStream tokens = new CommonTokenStream(lexer)
def parser = new GroovyParser(tokens)
ParseTree tree = parser.blockStatement()

println("=" * 60)
new ParseTreeWalker().walk(new ParseTreeListener() {
    def indent = 0;
    @Override void visitTerminal(@NotNull TerminalNode node) {
        println('.\t' * indent + "$node")
    }

    @Override void visitErrorNode(@NotNull ErrorNode node) {
        println('.\t' * indent + "ERROR: $node")
    }

    @Override void enterEveryRule(@NotNull ParserRuleContext ctx) {
        println('.\t' * indent + "${GroovyParser.ruleNames[ctx.ruleIndex]}: {")
        indent++
    }

    @Override void exitEveryRule(@NotNull ParserRuleContext ctx) {
        indent--
        println('.\t' * indent + "}")
    }
}, tree)

println("=" * 60)

println(tree.toStringTree())
