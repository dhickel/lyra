import lang.ast.ASTNode;
import parse.*;
import util.ASTPrinter;
import util.exceptions.CompExcept;
import util.exceptions.InvalidGrammarException;
import util.exceptions.ParseError;
import org.junit.jupiter.api.Test;

import java.util.List;

public class TestForms {

    static final String S_EXPR = "(test_func 1 2.0 30)";
    static final String S_EXPR_OP = "(- 10 20 30 (* (+ 10 10) (+ 20 -20)))";
    static final String PRED_EXPR = "((> 10 4) -> 420 : (* 6 9))";
    static final String PRED_ELSE_ONLY = "let x : I32 = ((fake_function) : 10)";
    static final String LAMBDA_EXPR = "(=> |x : I32 | (* 10 x))";
    static final String LAMBDA_EXPR_TYPES = "(=> : I32 |x: I32, y: I32| ((> x y) -> 1 : 0))";
    static final String LAMBDA_EXPR_FORM = "(|x: I32 | (* x 20))";
    static final String VALUES = "10 Identifier (* 20 30) 20 test";
    static final String NAME_SPACE = " namespace->::Test[function call]";
    static final String NAME_SPACE2 = " namespace->::Test[function call]:.field";
    static final String F_EXPR = " ::Test[function call]:.field::func_call2[10 20 30]";
    static final String F_EXPR2 = " ::func_call2[10 20 30]";
    static final String S_F_EXPR = " (::Test[function call]:.field::func_call2[10 20 30] 10 20 30)";
    static final String LET = " let x : I32  = (::Test[function call]:.field::func_call2[10 20 30] 10 20 30)";
    static final String ASSIGN = "x  := (* 10 20)";
    static final String BLOCK_EXPR = """
            {
                let x: I32 =  10
                x := (+ x 10)
                (test 20 (* x 10 30))
            }""";
    static final String BLOCK_EXPR2 = """
            let x :I32 = {
                let x: I32 =  10
                x := (+ x 10)
                (test 20 (* x 10 30))
            }""";
    static final String LAMBDA_BLOCK = """
            let x : Fn<I32;I32> = (=> |y :I32| {
                let x: I32 =  10
                x := (+ x 10)
                (* x y 10 30)
            })""";
    static final String FUNC_LET = "let x: Fn<I32, I32; I32> = (=> |x y| (* x y))";

    static final String PRED_FORM_CALL = "::TestFunction[|t| (* t 10)] ";

    static final List<String> forms = List.of(
            S_EXPR,
            S_EXPR_OP,
            PRED_EXPR,
            LAMBDA_EXPR,
            LAMBDA_EXPR_TYPES,
            LAMBDA_EXPR_FORM, PRED_ELSE_ONLY,
            VALUES,
            NAME_SPACE,
            NAME_SPACE2,
            F_EXPR,
            F_EXPR2,
            S_F_EXPR,
            LET,
            ASSIGN,
            BLOCK_EXPR,
            BLOCK_EXPR2,
            LAMBDA_BLOCK,
            FUNC_LET,
            PRED_FORM_CALL
    );


    @Test
    void testLexer() {
        for (var f : forms) {
            var t = Lexer.process(f);
            System.out.println(t);

        }
    }

    @Test
    void testGrammar() throws CompExcept {
        for (var f : forms) {
            List<Token> tokens = Lexer.process(f);

            System.out.println(f);
            System.out.println(tokens);


            try {
                ASTNode.CompilationUnit node = new Parser.LangParser(tokens).process().throwOnErr();
                ASTPrinter.debugPrint(node);
            } catch (CompExcept e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }

            // System.out.println(node);

        }
    }


}
