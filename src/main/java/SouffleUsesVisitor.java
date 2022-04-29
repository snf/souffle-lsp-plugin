import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import visitors.SouffleBaseVisitor;
import visitors.SouffleLexer;
import visitors.SouffleParser;

import java.util.*;

public class SouffleUsesVisitor extends SouffleBaseVisitor<SouffleSymbol> {

    private final String documentUri;
    SouffleParser parser;
    SouffleContext documentContext;
    ProjectContext projectContext;

    ArrayDeque<ArrayDeque<SouffleSymbol>> currentScope;
    ArrayDeque<SouffleContext> currentContext;

    public SouffleUsesVisitor(SouffleParser parser, String documentContext) {
        this.parser = parser;
        this.currentScope = new ArrayDeque<>();
        this.currentContext  = new ArrayDeque<>();
        this.documentUri = documentContext;
        this.projectContext = ProjectContext.getInstance();
        this.documentContext = projectContext.getDocumentContext(documentContext);
    }


    public SouffleContext getDocumentContext() {
        return documentContext;
    }

    public SouffleSymbol findDecl(SouffleSymbol symbol){
        if(symbol.getDeclaration() != null){
            return symbol.getDeclaration();
        }
        for(SouffleContext context: currentContext){
            SouffleSymbol decl = filterDecl(symbol, context);
            if (decl != null) {
                if(decl.getURI() == null){
                    decl.setURI(documentUri);
                }
                return decl;
            }
        }
        for(Map.Entry<String, SouffleContext> document: projectContext.getDocuments().entrySet()){
            SouffleSymbol decl = filterDecl(symbol, document.getValue());
            if (decl != null) {
                if(decl.getURI() == null){
                    decl.setURI(document.getKey());
                }
                return decl;
            }
        }
//        LSClientLogger.getInstance().reportError(symbol.getRange(), documentUri, "No declaration found for " + symbol.getName());
        return null;
    }

    private SouffleSymbol filterDecl(SouffleSymbol symbol, SouffleContext context) {
        List<SouffleSymbol> scope = context.getSymbols(symbol.getName());
        String componentUri = null;
        boolean inComponent = false;
        if(symbol.getComponent() != null){
            inComponent = true;
            SouffleVariable componentDecl = ((SouffleVariable) symbol.getComponent().getDeclaration());
            System.err.println("Component decl " + componentDecl);
            if(componentDecl != null){
                SouffleComponent componentParent = (SouffleComponent) componentDecl.getType().getDeclaration();
                if(componentParent != null){
                    scope = componentParent.getScope().get(symbol.getName());
                    componentUri = componentParent.getURI();
                }
            }
        }
        if(scope == null){
            return null;
        }
        Optional<SouffleSymbol> decl = scope.stream().filter(symbol1 -> {
            switch (symbol1.getKind()){
                case RELATION_DECL:
                case COMPONENT_DECL:
                case COMPONENT_INIT:
                case TYPE_DECL:
                    return true;
                default:
                    return false;
            }
        }).findFirst();
        if(inComponent){
            String finalComponentUri = componentUri;
            decl.ifPresent(symbol1 -> {
                symbol1.setURI(finalComponentUri);
            });
        }
        return decl.orElse(null);
    }

    public Range toRange(ParserRuleContext ctx){
        Position start = new Position(ctx.getStart().getLine() - 1, ctx.getStart().getCharPositionInLine());
        Position end = new Position(ctx.getStop().getLine() - 1, ctx.getStop().getCharPositionInLine() + ctx.getStop().getText().length());
        return new Range(start, end);
    }
    public Range toRange(TerminalNode ctx){
        Position start = new Position(ctx.getSymbol().getLine() - 1, ctx.getSymbol().getCharPositionInLine());
        Position end = new Position(ctx.getSymbol().getLine() - 1, ctx.getSymbol().getCharPositionInLine() + ctx.getSymbol().getText().length());
        return new Range(start, end);
    }

    @Override
    public SouffleSymbol visitProgram(SouffleParser.ProgramContext ctx) {
        currentContext.push(documentContext);
//        System.err.println(ctx.toStringTree(parser));

        super.visitProgram(ctx);

//        System.err.println(documentContext);
        return null;
    }

    @Override
    public SouffleSymbol visitComponent_decl(SouffleParser.Component_declContext ctx) {
        assert currentContext.peek() != null;
        SouffleContext documentContext = currentContext.peek();
        Position start = new Position(ctx.getStart().getLine() - 1, ctx.getStart().getCharPositionInLine());
        SouffleContext componentContext = documentContext.getFromSubContext(new Range(start, start));
        System.err.println(componentContext);
        currentContext.push(componentContext);
        ctx.component_body().accept(this);
        SouffleComponent component = (SouffleComponent) componentContext.getContextSymbols().get(0);
        component.addToScope(componentContext.getScope());
        currentContext.pop();
        return null;
    }

    @Override
    public SouffleSymbol visitComponent_init(SouffleParser.Component_initContext ctx) {
        assert currentContext.peek() != null;
        SouffleContext documentContext = currentContext.peek();
        SouffleContext componentInitContext = new SouffleContext(SouffleContextType.COMPONENT, toRange(ctx));

//        component.
        SouffleSymbol parent = ctx.component_type().accept(this);

        SouffleType componentType = new SouffleType(parent.getName(), parent.getRange());
        componentType.setDeclaration(findDecl(componentType));
        SouffleVariable component = new SouffleVariable(ctx.IDENT().getText(),componentType,null, toRange(ctx.IDENT()), true);
        component.setDeclaration(component);
        component.setURI(documentUri);

        componentInitContext.addContextSymbol(component);
        componentInitContext.addContextSymbol(componentType);
//        componentInitContext.addToContextScope(component);
        documentContext.addToContextScope(component);
        documentContext.addToSubContext(componentInitContext);

        return null;
    }

    @Override
    public SouffleSymbol visitComponent_type(SouffleParser.Component_typeContext ctx) {
        return new SouffleSymbol(ctx.IDENT().getText(), SouffleSymbolType.VARIABLE, toRange(ctx.IDENT()));
    }

    @Override
    public SouffleSymbol visitDirective_head(SouffleParser.Directive_headContext ctx) {
        SouffleContext directiveContext = new SouffleContext(SouffleContextType.DIRECTIVE,toRange(ctx));
        assert currentContext.peek() != null;
        SouffleContext documentContext = currentContext.peek();
        documentContext.addToSubContext(directiveContext);
        String directive = ctx.directive_head_decl().getText();

        currentScope.push(new ArrayDeque<>());
        ctx.directive_list().accept(this);
        ArrayDeque<SouffleSymbol> directiveList =  currentScope.pop();
        for(SouffleSymbol symbol: directiveList){
            SouffleRelation relation = new SouffleRelation(symbol.getName(), symbol.getRange());
            relation.setDirective(directive);
            relation.setDeclaration(findDecl(relation));
            directiveContext.addContextSymbol(relation);
            documentContext.addToContextScope(relation);
        }

        return null;
    }

    @Override
    public SouffleSymbol visitRelation_decl(SouffleParser.Relation_declContext ctx) {
        currentScope.push(new ArrayDeque<>());
        ctx.relation_names().accept(this);
        ArrayDeque<SouffleSymbol> relationNames = currentScope.pop();

        currentScope.push(new ArrayDeque<>());
        ctx.attributes_list().accept(this);
        ArrayDeque<SouffleSymbol> attributes = currentScope.pop();

        SouffleRelation declSymbol = (SouffleRelation) findDecl(relationNames.getFirst());
        int i=0;
        for (SouffleSymbol attribute : attributes) {
            SouffleVariable arg = (SouffleVariable) attribute;
            declSymbol.getArgs().get(i).getType().setDeclaration(findDecl(arg.getType()));
            i++;
        }
//        currentContext.pop();
        return null;
    }

    @Override
    public SouffleSymbol visitType_decl(SouffleParser.Type_declContext ctx) {
        if(ctx.qualified_name() != null){
            SouffleSymbol parent = ctx.qualified_name().accept(this);
            parent.setDeclaration(findDecl(parent));
        } else if(ctx.union_type_list() != null){
            currentScope.push(new ArrayDeque<>());
            ctx.union_type_list().accept(this);
            ArrayDeque<SouffleSymbol> types = currentScope.pop();
            SouffleType declSymbol = (SouffleType) findDecl(new SouffleType(ctx.IDENT().getText(), toRange(ctx)));
            int i=0;
            for(SouffleSymbol type: types){
                declSymbol.getUnion().get(i).setDeclaration(findDecl(type));
                i++;
            }

//            types.forEach(symbol -> symbol.setDeclaration(findDecl(symbol)));
        }
//        System.err.println(documentContext);

        return null;
    }

    @Override
    public SouffleSymbol visitRelation_names(SouffleParser.Relation_namesContext ctx) {
        SouffleSymbol relationName = new SouffleSymbol(ctx.IDENT().getText(), SouffleSymbolType.VARIABLE, toRange(ctx));
        assert currentScope.peek() != null;
        currentScope.peek().push(relationName);
        return super.visitRelation_names(ctx);
    }

    @Override
    public SouffleSymbol visitRule_def(SouffleParser.Rule_defContext ctx) {
        SouffleContext ruleContext = new SouffleContext(SouffleContextType.RULE,toRange(ctx));
        assert currentContext.peek() != null;
        SouffleContext documentContext = currentContext.peek();
        currentContext.push(ruleContext);
        documentContext.addToSubContext(ruleContext);

        currentScope.push(new ArrayDeque<>());
        ctx.head().accept(this);
        ArrayDeque<SouffleSymbol> rules = currentScope.pop();
        SouffleRule rule = null;
        for(SouffleSymbol symbol: rules){
            SouffleRelation relationSymbol = (SouffleRelation) symbol;
            rule = new SouffleRule(symbol.getName(), symbol.getRange());
            SouffleSymbol decl = findDecl(rule);
            rule.setDeclaration(decl);
            documentContext.addToContextScope(rule);
            ruleContext.addContextSymbol(rule);
            List<SouffleVariable> args = relationSymbol.getArgs();
            for (int i = 0; i < args.size(); i++) {
                SouffleVariable arg = args.get(i);
                rule.addArg(arg);
                if(decl != null){
                    arg.setType(((SouffleRelation)decl).getArgs().get(i).getType());
                }
            }

        }

        assert rule != null;
        for(SouffleVariable argSymbol : rule.getArgs()){
            SouffleContext variableContext = new SouffleContext(SouffleContextType.VARIABLE, new Range(argSymbol.getRange().getStart(), argSymbol.getRange().getEnd()));
            variableContext.addContextSymbol(argSymbol);
            ruleContext.addToContextScope(argSymbol);
            ruleContext.addToSubContext(variableContext);

        }
        currentScope.push(new ArrayDeque<>());
        ctx.body().accept(this);
        ArrayDeque<SouffleSymbol> body = currentScope.pop();

        for(SouffleSymbol bodySymbol: body){
            rule.addToBody(bodySymbol);
            SouffleSymbol decl = findDecl(bodySymbol);
            bodySymbol.setDeclaration(decl);

            if(decl != null && bodySymbol.getKind() == SouffleSymbolType.RELATION_USE){
                List<SouffleVariable> args = ((SouffleRelation) bodySymbol).getArgs();
                for (int i = 0; i < args.size(); i++) {
                    SouffleVariable arg = args.get(i);
                    arg.setType(((SouffleRelation)decl).getArgs().get(i).getType());
                }
            }
        }

        currentContext.pop();

        return null;
    }

    @Override
    public SouffleSymbol visitHead(SouffleParser.HeadContext ctx) {
        SouffleSymbol atom = ctx.atom().accept(this);
        assert currentScope.peek() != null;
        currentScope.peek().push(atom);
        return super.visitHead(ctx);
    }

    @Override
    public SouffleSymbol visitBody(SouffleParser.BodyContext ctx) {
        assert currentContext.peek() != null;
        SouffleContext ruleContext = currentContext.peek();
        currentScope.push(new ArrayDeque<>());
        ctx.disjunction().accept(this);
        ArrayDeque<SouffleSymbol> bodyList = currentScope.pop();
        currentScope.push(bodyList);
        for (SouffleSymbol bodySymbol: bodyList){
            if(bodySymbol.getKind() == SouffleSymbolType.RELATION_USE){
                SouffleRelation relation = (SouffleRelation) bodySymbol;
                SouffleContext inRuleContext = new SouffleContext(SouffleContextType.RELATION_USE, relation.getRange());
                inRuleContext.addContextSymbol(relation);

                ruleContext.addToSubContext(inRuleContext);
                ruleContext.addToContextScope(relation);
                for(SouffleVariable arg: relation.getArgs()){
                    SouffleContext inRelationContext = new SouffleContext(SouffleContextType.VARIABLE, arg.getRange());
                    inRelationContext.addContextSymbol(arg);

                    ruleContext.addToSubContext(inRelationContext);
                    ruleContext.addToContextScope(arg);
                }
            } else if(bodySymbol.getKind() == SouffleSymbolType.CONSTRAINT){
                SouffleConstraint constraint = (SouffleConstraint) bodySymbol;
                for(SouffleVariable arg: constraint.getArgs()){
                    SouffleContext inRelationContext = new SouffleContext(SouffleContextType.VARIABLE, arg.getRange());
                    inRelationContext.addContextSymbol(arg);

                    ruleContext.addToSubContext(inRelationContext);
                    ruleContext.addToContextScope(arg);
                }
            }
        }

        return null;
    }

    @Override
    public SouffleSymbol visitConjunction(SouffleParser.ConjunctionContext ctx) {
        SouffleSymbol atom = ctx.term().accept(this);
        if(atom != null){
            assert currentScope.peek() != null;
            currentScope.peek().push(atom);
        }
        return super.visitConjunction(ctx);
    }

    @Override
    public SouffleSymbol visitConstraint(SouffleParser.ConstraintContext ctx) {
        SouffleConstraint constraint = new SouffleConstraint(ctx.getText(), toRange(ctx));
        if(!ctx.arg().isEmpty()){
            for (int i = 0; i < ctx.arg().size(); i++) {
                currentScope.push(new ArrayDeque<>());
                ctx.arg(i).accept(this);
                ArrayDeque<SouffleSymbol> subArgs = currentScope.pop();
                for(SouffleSymbol subArg: subArgs){
                    constraint.addArg((SouffleVariable) subArg);
                }
            }
        }
        return constraint;
    }

    @Override
    public SouffleSymbol visitFact(SouffleParser.FactContext ctx) {
        assert currentContext.peek() != null;
        SouffleContext documentContext = currentContext.peek();
        SouffleContext factContext = new SouffleContext(SouffleContextType.RELATION_USE,toRange(ctx));
        documentContext.addToSubContext(factContext);
        currentContext.push(factContext);
        SouffleRelation fact = (SouffleRelation) ctx.atom().accept(this);
        currentContext.pop();
        SouffleSymbol decl = findDecl(fact);
        fact.setDeclaration(decl);

        factContext.addContextSymbol(fact);
        documentContext.addToContextScope(fact);

        List<SouffleVariable> args = fact.getArgs();
        for (int i = 0; i < args.size(); i++) {
            SouffleVariable arg = args.get(i);
            if(decl != null) {
                arg.setType(((SouffleRelation)decl).getArgs().get(i).getType());
            }
            SouffleContext factArgContext = new SouffleContext(SouffleContextType.VARIABLE, arg.getRange());
            factArgContext.addContextSymbol(arg);
            factContext.addToSubContext(factArgContext);
        }

        return null;
    }

    @Override
    public SouffleSymbol visitAtom(SouffleParser.AtomContext ctx) {
        SouffleSymbol atomName = ctx.qualified_name().accept(this);
        currentScope.push(new ArrayDeque<>());
        ctx.arg_list().accept(this);
        ArrayDeque<SouffleSymbol> args = currentScope.pop();
        SouffleRelation atom = new SouffleRelation(atomName.getName(), atomName.getRange());
        atom.setComponent(atomName.getComponent());
        for(SouffleSymbol attribute: args){
            SouffleVariable arg = (SouffleVariable) attribute;
            atom.addArg(arg);
        }
        return atom;
    }
    @Override
    public SouffleSymbol visitArg(SouffleParser.ArgContext ctx) {
        if(!ctx.arg().isEmpty()){
            for (int i = 0; i < ctx.arg().size(); i++) {
                currentScope.push(new ArrayDeque<>());
                ctx.arg(i).accept(this);
                ArrayDeque<SouffleSymbol> subArgs = currentScope.pop();
                for(SouffleSymbol subArg: subArgs){
                    if(((SouffleVariable)subArg).getValue() == null){
                        assert currentScope.peek() != null;
                        currentScope.peek().push(subArg);
                    }
                }
            }
        } else {
            SouffleVariable variable = new SouffleVariable(ctx.getText(), toRange(ctx));
            if(ctx.IDENT() == null){
                variable.setValue(ctx.getText());
            }
            assert currentScope.peek() != null;
            currentScope.peek().push(variable);
        }
//        super.visitArg(ctx);
        return null;
    }

    @Override
    public SouffleSymbol visitNon_empty_arg_list(SouffleParser.Non_empty_arg_listContext ctx) {
        if(ctx.non_empty_arg_list() != null){
            ctx.non_empty_arg_list().accept(this);
        }
        currentScope.push(new ArrayDeque<>());
        ctx.arg().accept(this);
        ArrayDeque<SouffleSymbol> args = currentScope.pop();
        for(SouffleSymbol arg: args){
            assert currentScope.peek() != null;
            currentScope.peek().push(arg);
        }

        return null;
    }

    @Override
    public SouffleSymbol visitUnion_type_list(SouffleParser.Union_type_listContext ctx) {
        SouffleSymbol type = ctx.qualified_name().accept(this);
        assert currentScope.peek() != null;
        currentScope.peek().push(type);
        return super.visitUnion_type_list(ctx);
    }

    @Override
    public SouffleSymbol visitRecord_type_list(SouffleParser.Record_type_listContext ctx) {
        currentScope.push(new ArrayDeque<>());
        super.visitRecord_type_list(ctx);
        currentScope.pop();
        return null;
    }

    @Override
    public SouffleSymbol visitAdt_branch(SouffleParser.Adt_branchContext ctx) {
        currentScope.push(new ArrayDeque<>());
        super.visitAdt_branch(ctx);
        currentScope.pop();
        return null;
    }

    @Override
    public SouffleSymbol visitNon_empty_attributes(SouffleParser.Non_empty_attributesContext ctx) {
        SouffleVariable attribute = (SouffleVariable) ctx.attribute().accept(this);
        assert currentScope.peek() != null;
        currentScope.peek().push(attribute);
        return super.visitNon_empty_attributes(ctx);
    }

    @Override
    public SouffleSymbol visitRelation_directive_list(SouffleParser.Relation_directive_listContext ctx) {
        SouffleSymbol attribute =  ctx.qualified_name().accept(this);
        assert currentScope.peek() != null;
        currentScope.peek().push(attribute);
        return super.visitRelation_directive_list(ctx);
    }


    @Override
    public SouffleSymbol visitAttribute(SouffleParser.AttributeContext ctx) {
        String name = ctx.IDENT().getText();
        SouffleSymbol typeSymbol = ctx.qualified_name().accept(this);

        SouffleType type = new SouffleType(typeSymbol.getName(), typeSymbol.getRange());
        type.setComponent(typeSymbol.getComponent());
        return new SouffleVariable(name, type, toRange(ctx.IDENT()));
    }
    @Override
    public SouffleSymbol visitQualified_name(SouffleParser.Qualified_nameContext ctx) {
        SouffleSymbol symbol = new SouffleSymbol(ctx.IDENT().getText(), SouffleSymbolType.VARIABLE, toRange(ctx.IDENT()));
        if(ctx.qualified_name() != null){
            assert currentContext.peek() != null;
            SouffleContext factContext = currentContext.peek();
            SouffleSymbol component = ctx.qualified_name().accept(this);
            component.setDeclaration(findDecl(component));
            symbol.setComponent(component);

            factContext.addContextSymbol(component);
            factContext.addToContextScope(component);
        }
        return symbol;
    }
}
