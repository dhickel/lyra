```EBNF 

SymbolRef ::= [a-zA-Z_][a-zA-Z0-9_?-]*;
Value ::= Number | Lambda | Array | Object | String | Nil | Bool;
Operation ::= '+' | '-' | '*' | '/' | '^' | '%' | '==' | 'and' | 'or' | '!=' | 'eq?' | '!eq?'
| 'eqt?' | '!eqt?' | 'eqv?' | '!eqv?' | 'nor' | 'nand' | 'xnor' | 'not' | '>' | '<' | '<=' | '>='

Modifier ::= '@' SymbolRef;

Number ::= Integer | Float;
Integer ::= I8 | I16 | I32 | I64 | U8 | U16 | U32 | U64;
Float ::= F32 | F64;

Lambda := LambdaExpr;
Array ::= '[' Value* ']';
Tuple ::= '(' Value* ')';
Object ::= OBJECT;
String ::= String;
Nil ::= '()' | '#Nil';
Bool ::=  '#True' | '#False';

Type ::= SymbolRef | 'Fn<' Type* ';' Type '>' | 'Array<' Type '>';

Variant := Type Where Type<+Type>;
Sequence ::= Expr Where Type<Sequence>;
Supplier ;;= Expr Where Type<Option>;

Predicate ::= Expr Where Type<Bool>;
Range ::= [Number] '..' [ '=' ] [Number] [(':' Number)];

Expr ::= SExpr | FExpr | VExpr | LambdaExpr | BExpr | CondExpr;

Argument ::= { Modifier } Expr;
Parameter ::={ Modifier } SymbolRef [(':' Type)];

NamespaceAccess ::= SymbolRef '->';
FieldAccess ::= ':.' SymbolRef;
1MethodAccess ::= '::' SymbolRef;
MethodCall ::= MethodAccess FCall;
MemberAccessChain ::= ((FieldAccess | MethodCall)*  [MethodAccess])+;
NameSpaceChain ::= { NameSpaceAccess }- ;

FCall ::=  '[' Argument* ']';

LambdaExpr ::= '(' '=>' [Type] LambdaForm ')' | '(' LambdaForm ')';
VExpr ::= SymbolRef | Value;
SExpr ::= '(' Expr | Operation  ({ Expr } | [ PredicateForm ]) ')';
FExpr ::= ([NamespaceAccess] [ SymbolRef ] [MemberAccessChain])  ;
BlockExpr ::= '{'  { Expr | Stmnt } '}';
BExpr ::= Match | Iter;

Match ::= '(' 'Match' [FCall] (MatchForm ThenForm)+')' | '((Match' {Parameter}')' (MatchForm ThenForm)+  ')';
Iter ::= [ '(' ] Iter '[' (Range | Sequence | Supplier) ']' {[{MethodCall}] [MethodAccess]}- [ ')'];

PredicateForm ::= ([ThenForm] | [ElseForm] | [ThenElseForm]);
ThenElseForm :: = ThenForm ElseForm;
ThenForm ::= '->' Expr;
ElseForm ::= '-' Expr;

MatchForm ::= Variant BoundForm | Predicate | Variant BoundForm Predicate;

BoundForm  ::= '|' { Parameter } '|';
LambdaForm ::= BoundForm Expr;

Stmnt ::= LetStmnt | AssignStmnt;

LetStmnt ::= 'let' SymbolRef [(':' Type)] Modifier* '=' Expr;
AssignStmnt ::= SymbolRef ':=' Expr;
```