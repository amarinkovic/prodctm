package pro.documentum.jdo.query;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.MetaDataUtils;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.query.compiler.CompilationComponent;
import org.datanucleus.query.compiler.QueryCompilation;
import org.datanucleus.query.evaluator.AbstractExpressionEvaluator;
import org.datanucleus.query.expression.Expression;
import org.datanucleus.query.expression.InvokeExpression;
import org.datanucleus.query.expression.Literal;
import org.datanucleus.query.expression.OrderExpression;
import org.datanucleus.query.expression.ParameterExpression;
import org.datanucleus.query.expression.PrimaryExpression;
import org.datanucleus.store.query.Query;
import org.datanucleus.store.schema.table.Table;
import org.datanucleus.store.types.SCO;
import org.datanucleus.store.types.converters.TypeConverter;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

import pro.documentum.jdo.query.expression.DQLAggregateExpression;
import pro.documentum.jdo.query.expression.DQLBooleanExpression;
import pro.documentum.jdo.query.expression.DQLExpression;
import pro.documentum.jdo.query.expression.DQLFieldExpression;
import pro.documentum.jdo.query.expression.DQLLiteral;
import pro.documentum.jdo.util.DNMetaData;
import pro.documentum.jdo.util.DNQueries;

public class QueryToDQLMapper extends AbstractExpressionEvaluator {

    private final ExecutionContext _ec;

    private final AbstractClassMetaData _cmd;

    private final Query _q;

    private final QueryCompilation _qc;

    private final Map _params;

    private int _positionalParamNumber = -1;

    private CompilationComponent _cc;

    private String _filterText;

    private String _orderText;

    private String _resultText;

    private boolean _filterComplete = true;

    private boolean _resultComplete = true;

    private boolean _orderComplete = true;

    private boolean _precompilable = true;

    private final Deque<DQLExpression> _exprs = new ArrayDeque<DQLExpression>();

    public QueryToDQLMapper(final QueryCompilation compilation,
            final Map params, final AbstractClassMetaData cmd,
            final ExecutionContext ec, final Query q) {
        _ec = ec;
        _q = q;
        _qc = compilation;
        _params = params;
        _cmd = cmd;
    }

    public void compile(final DQLQueryCompilation dqlCompilation) {
        compileFilter();
        compileResult();
        compileOrder();

        dqlCompilation.setPrecompilable(_precompilable);
        dqlCompilation.setFilterComplete(_filterComplete);
        dqlCompilation.setResultComplete(_resultComplete);
        dqlCompilation.setOrderComplete(_orderComplete);

        Long rangeFrom = null;
        Long rangeTo = null;
        if (_filterComplete && _orderComplete) {
            if (_q.getRangeFromIncl() > 0) {
                rangeFrom = _q.getRangeFromIncl();
            } else {
                rangeFrom = null;
            }
            if (_q.getRangeToExcl() != Long.MAX_VALUE) {
                rangeTo = _q.getRangeToExcl();
            } else {
                rangeTo = null;
            }
            if (rangeFrom != null || rangeTo != null) {
                dqlCompilation.setRangeComplete(true);
            }
        }

        String resultText = null;
        if (_resultComplete) {
            resultText = _resultText;
        }
        String dqlText = DNQueries.getDqlTextForQuery(_ec, _cmd, _qc
                .getCandidateAlias(), _q.isSubclasses(), _filterText,
                resultText, _orderText, rangeFrom, rangeTo);
        dqlCompilation.setDqlText(dqlText);
    }

    protected void compileFilter() {
        if (_qc.getExprFilter() == null) {
            return;
        }

        _cc = CompilationComponent.FILTER;

        try {
            _qc.getExprFilter().evaluate(this);
            DQLExpression dqlExpr = _exprs.pop();
            _filterText = dqlExpr.getDqlText();
        } catch (Exception e) {
            NucleusLogger.QUERY.error("Compilation of filter "
                    + "to be evaluated completely "
                    + "in-datastore was impossible : " + e.getMessage(), e);
            _filterComplete = false;
        }

        _cc = null;
    }

    protected void compileOrder() {
        if (_qc.getExprOrdering() == null) {
            return;
        }

        _cc = CompilationComponent.ORDERING;

        try {
            doCompileOrder();
        } catch (Exception e) {
            NucleusLogger.QUERY.error("Compilation of ordering "
                    + "to be evaluated completely in-datastore "
                    + "was impossible : " + e.getMessage(), e);
            _orderComplete = false;
        }

        _cc = null;
    }

    private void doCompileOrder() {
        StringBuilder orderStr = new StringBuilder();
        Expression[] orderingExpr = _qc.getExprOrdering();
        for (int i = 0; i < orderingExpr.length; i++) {
            OrderExpression orderExpr = (OrderExpression) orderingExpr[i];
            orderExpr.evaluate(this);
            DQLExpression dqlExpr = _exprs.pop();
            orderStr.append(dqlExpr.getDqlText());
            String orderDir = orderExpr.getSortOrder();
            if ("descending".equalsIgnoreCase(orderDir)) {
                orderStr.append(" DESC");
            }
            if (i < orderingExpr.length - 1) {
                orderStr.append(",");
            }
        }
        _orderText = orderStr.toString();
    }

    protected void compileResult() {
        if (_qc.getExprResult() == null) {
            return;
        }

        _cc = CompilationComponent.RESULT;
        _resultComplete = true;

        try {
            doCompileResult();
        } catch (Exception e) {
            NucleusLogger.GENERAL.info("Query result clause "
                    + StringUtils.objectArrayToString(_qc.getExprResult())
                    + " not totally supported via DQL "
                    + "so will be processed in-memory");
            _resultComplete = false;
        }

        _cc = null;
    }

    private void doCompileResult() {
        StringBuilder str = new StringBuilder();
        Expression[] resultExprs = _qc.getExprResult();
        int i = 0;
        for (Expression expr : resultExprs) {
            DQLExpression dqlExpression = null;
            if (expr instanceof PrimaryExpression) {
                PrimaryExpression primExpr = (PrimaryExpression) expr;
                processPrimaryExpression(primExpr);
                dqlExpression = _exprs.pop();
                str.append(dqlExpression.getDqlText());
            } else if (expr instanceof Literal) {
                processLiteral((Literal) expr);
                dqlExpression = _exprs.pop();
                str.append(dqlExpression.getDqlText());
            } else if (expr instanceof ParameterExpression) {
                processParameterExpression((ParameterExpression) expr);
                dqlExpression = _exprs.pop();
                str.append(dqlExpression.getDqlText());
            } else if (expr instanceof InvokeExpression) {
                processInvokeExpression((InvokeExpression) expr, str);
            } else {
                NucleusLogger.GENERAL.info("Query result expression " + expr
                        + " not supported via DQL "
                        + "so will be processed in-memory");
                _resultComplete = false;
                break;
            }
            if (i < resultExprs.length - 1) {
                str.append(",");
            }
            i++;
        }
        _resultText = str.toString();
    }

    private void processInvokeExpression(final InvokeExpression invokeExpr,
            final StringBuilder builder) {
        if (invokeExpr.getLeft() != null) {
            return;
        }
        List<Expression> argExprs = invokeExpr.getArguments();
        if (argExprs == null || argExprs.size() != 1) {
            throw new NucleusUserException("Invalid number of arguments to MAX");
        }

        Expression argExpr = argExprs.get(0);
        if (argExpr instanceof PrimaryExpression) {
            processPrimaryExpression((PrimaryExpression) argExpr);
        } else {
            throw new NucleusUserException("Invocation of static method "
                    + invokeExpr.getOperation() + " with arg of type "
                    + argExpr.getClass().getName()
                    + " not supported in-datastore");
        }

        DQLExpression aggrArgExpr = _exprs.pop();
        if (isAggregateExpr(invokeExpr)) {
            DQLExpression aggExpr = new DQLAggregateExpression(invokeExpr
                    .getOperation(), aggrArgExpr);
            builder.append(aggExpr.getDqlText());
        } else {
            throw new NucleusUserException("Invocation of static method "
                    + invokeExpr.getOperation() + " not supported in-datastore");
        }
    }

    private boolean isAggregateExpr(final InvokeExpression invokeExpr) {
        String op = invokeExpr.getOperation();
        return "MAX".equalsIgnoreCase(op) || "MIN".equalsIgnoreCase(op)
                || "SUM".equalsIgnoreCase(op) || "AVG".equalsIgnoreCase(op)
                || "COUNT".equalsIgnoreCase(op);
    }

    @Override
    protected Object processAndExpression(final Expression expr) {
        DQLBooleanExpression right = (DQLBooleanExpression) _exprs.pop();
        DQLBooleanExpression left = (DQLBooleanExpression) _exprs.pop();
        DQLBooleanExpression andExpr = new DQLBooleanExpression(left,
                Expression.OP_AND, right);
        _exprs.push(andExpr);
        return andExpr;
    }

    @Override
    protected Object processOrExpression(final Expression expr) {
        DQLBooleanExpression right = (DQLBooleanExpression) _exprs.pop();
        DQLBooleanExpression left = (DQLBooleanExpression) _exprs.pop();
        DQLBooleanExpression andExpr = new DQLBooleanExpression(left,
                Expression.OP_OR, right);
        _exprs.push(andExpr);
        return andExpr;
    }

    private Object processExpression(final Expression expr,
            final Expression.DyadicOperator op) {
        return processExpression(expr, op, op);
    }

    private Object processExpression(final Expression expr,
            final Expression.DyadicOperator op1,
            final Expression.DyadicOperator op2) {
        Object right = _exprs.pop();
        Object left = _exprs.pop();
        if (left instanceof DQLLiteral && right instanceof DQLFieldExpression) {
            String field = ((DQLFieldExpression) right).getFieldName();
            Object value = ((DQLLiteral) left).getValue();
            DQLExpression dqlExpression = new DQLBooleanExpression(field,
                    value, op1);
            _exprs.push(dqlExpression);
            return dqlExpression;
        } else if (left instanceof DQLFieldExpression
                && right instanceof DQLLiteral) {
            String field = ((DQLFieldExpression) left).getFieldName();
            Object value = ((DQLLiteral) right).getValue();
            DQLExpression dqlExpression = new DQLBooleanExpression(field,
                    value, op2);
            _exprs.push(dqlExpression);
            return dqlExpression;
        }
        return null;
    }

    @Override
    protected Object processEqExpression(final Expression expr) {
        Object result = processExpression(expr, Expression.OP_EQ);
        if (result != null) {
            return result;
        }
        return super.processEqExpression(expr);
    }

    @Override
    protected Object processNoteqExpression(final Expression expr) {
        Object result = processExpression(expr, Expression.OP_NOTEQ);
        if (result != null) {
            return result;
        }
        return super.processEqExpression(expr);
    }

    @Override
    protected Object processGtExpression(final Expression expr) {
        Object result = processExpression(expr, Expression.OP_LTEQ,
                Expression.OP_GT);
        if (result != null) {
            return result;
        }
        return super.processEqExpression(expr);
    }

    @Override
    protected Object processLtExpression(final Expression expr) {
        Object result = processExpression(expr, Expression.OP_GTEQ,
                Expression.OP_LT);
        if (result != null) {
            return result;
        }
        return super.processEqExpression(expr);
    }

    @Override
    protected Object processGteqExpression(final Expression expr) {
        Object result = processExpression(expr, Expression.OP_LT,
                Expression.OP_GTEQ);
        if (result != null) {
            return result;
        }
        return super.processEqExpression(expr);
    }

    @Override
    protected Object processLteqExpression(final Expression expr) {
        Object result = processExpression(expr, Expression.OP_GT,
                Expression.OP_LTEQ);
        if (result != null) {
            return result;
        }
        return super.processEqExpression(expr);
    }

    @Override
    protected Object processNotExpression(final Expression expr) {
        Object theExpr = _exprs.pop();
        if (theExpr instanceof DQLBooleanExpression) {
            DQLExpression dqlBooleanExpression = new DQLBooleanExpression(
                    (DQLBooleanExpression) theExpr, Expression.OP_NOT);
            _exprs.push(dqlBooleanExpression);
            return dqlBooleanExpression;
        }

        return super.processNotExpression(expr);
    }

    @Override
    protected Object processParameterExpression(final ParameterExpression expr) {
        Object paramValue = null;
        boolean paramValueSet = false;
        if (_params != null && !_params.isEmpty()) {
            if (_params.containsKey(expr.getId())) {
                paramValue = _params.get(expr.getId());
                paramValueSet = true;
            } else if (_params.containsKey(expr.getId())) {
                paramValue = _params.get(expr.getId());
                paramValueSet = true;
            } else {
                int position = _positionalParamNumber;
                if (_positionalParamNumber < 0) {
                    position = 0;
                }
                if (_params.containsKey(position)) {
                    paramValue = _params.get(position);
                    paramValueSet = true;
                    _positionalParamNumber = position + 1;
                }
            }
        }

        if (!paramValueSet) {
            _precompilable = false;
            throw new NucleusException("Parameter " + expr
                    + " is not currently set, so cannot complete the _qc");
        }

        if (paramValue instanceof Number) {
            DQLLiteral lit = new DQLLiteral(paramValue);
            _exprs.push(lit);
            _precompilable = false;
            return lit;
        }

        if (paramValue instanceof String) {
            DQLLiteral lit = new DQLLiteral(paramValue);
            _exprs.push(lit);
            _precompilable = false;
            return lit;
        }

        if (paramValue instanceof Character) {
            DQLLiteral lit = new DQLLiteral("" + paramValue);
            _exprs.push(lit);
            _precompilable = false;
            return lit;
        }

        if (paramValue instanceof Boolean) {
            DQLLiteral lit = new DQLLiteral(paramValue);
            _exprs.push(lit);
            _precompilable = false;
            return lit;
        }

        if (paramValue instanceof java.util.Date) {
            Object storedVal = paramValue;
            Class paramType = paramValue.getClass();
            if (paramValue instanceof SCO) {
                paramType = ((SCO) paramValue).getValue().getClass();
            }
            TypeConverter strConv = _ec.getTypeManager()
                    .getTypeConverterForType(paramType, String.class);
            TypeConverter longConv = _ec.getTypeManager()
                    .getTypeConverterForType(paramType, Long.class);
            if (strConv != null) {
                storedVal = strConv.toDatastoreType(paramValue);
            } else if (longConv != null) {
                storedVal = longConv.toDatastoreType(paramValue);
            }
            DQLLiteral lit = new DQLLiteral(storedVal);
            _exprs.push(lit);
            _precompilable = false;
            return lit;
        }

        if (paramValue == null) {
            DQLLiteral lit = new DQLLiteral(null);
            _exprs.push(lit);
            _precompilable = false;
            return lit;
        }

        NucleusLogger.QUERY
                .info("Don't currently support parameter values of type "
                        + paramValue.getClass().getName());

        return super.processParameterExpression(expr);
    }

    @Override
    protected Object processPrimaryExpression(final PrimaryExpression expr) {
        Expression left = expr.getLeft();
        if (left != null) {
            return super.processPrimaryExpression(expr);
        }

        if (expr.getId().equals(_qc.getCandidateAlias())) {
            DQLFieldExpression fieldExpr = new DQLFieldExpression(_qc
                    .getCandidateAlias());
            _exprs.push(fieldExpr);
            return fieldExpr;
        }

        String fieldName = getFieldNameForPrimary(expr);
        if (fieldName != null) {
            DQLFieldExpression fieldExpr = new DQLFieldExpression(_qc
                    .getCandidateAlias()
                    + "." + fieldName);
            _exprs.push(fieldExpr);
            return fieldExpr;
        }

        if (_cc == CompilationComponent.FILTER) {
            _filterComplete = false;
        }

        NucleusLogger.QUERY.debug(">> Primary " + expr
                + " is not stored in this Documentum type,"
                + " so unexecutable in datastore");

        return super.processPrimaryExpression(expr);
    }

    @Override
    protected Object processLiteral(final Literal expr) {
        Object litValue = expr.getLiteral();
        if (litValue instanceof BigDecimal) {
            DQLLiteral lit = new DQLLiteral(((BigDecimal) litValue)
                    .doubleValue());
            _exprs.push(lit);
            return lit;
        }
        if (litValue instanceof Number) {
            DQLLiteral lit = new DQLLiteral(litValue);
            _exprs.push(lit);
            return lit;
        }
        if (litValue instanceof String) {
            DQLLiteral lit = new DQLLiteral(litValue);
            _exprs.push(lit);
            return lit;
        }
        if (litValue instanceof Boolean) {
            DQLLiteral lit = new DQLLiteral(litValue);
            _exprs.push(lit);
            return lit;
        }
        if (litValue == null) {
            DQLLiteral lit = new DQLLiteral(null);
            _exprs.push(lit);
            return lit;
        }

        return super.processLiteral(expr);
    }

    protected String getFieldNameForPrimary(final PrimaryExpression expr) {
        List<String> tuples = expr.getTuples();
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }

        AbstractClassMetaData cmd = _cmd;
        Table table = DNMetaData.getStoreData(_ec, cmd).getTable();
        AbstractMemberMetaData embMmd = null;

        List<AbstractMemberMetaData> embMmds = new ArrayList<AbstractMemberMetaData>();
        boolean firstTuple = true;
        Iterator<String> iter = tuples.iterator();
        ClassLoaderResolver clr = _ec.getClassLoaderResolver();
        while (iter.hasNext()) {
            String name = iter.next();
            if (firstTuple && name.equals(_qc.getCandidateAlias())) {
                cmd = _cmd;
                continue;
            }

            AbstractMemberMetaData mmd = cmd.getMetaDataForMember(name);
            RelationType relationType = mmd.getRelationType(_ec
                    .getClassLoaderResolver());
            if (relationType == RelationType.NONE) {
                if (iter.hasNext()) {
                    throw new NucleusUserException("Query has reference to "
                            + StringUtils.collectionToString(tuples) + " yet "
                            + name + " is a non-relation field!");
                }
                if (embMmd != null) {
                    embMmds.add(mmd);
                    return table.getMemberColumnMappingForEmbeddedMember(
                            embMmds).getColumn(0).getName();
                }
                return table.getMemberColumnMappingForMember(mmd).getColumn(0)
                        .getName();
            }

            AbstractMemberMetaData emmd = null;
            if (!embMmds.isEmpty()) {
                emmd = embMmds.get(embMmds.size() - 1);
            }

            boolean embedded = MetaDataUtils.getInstance().isMemberEmbedded(
                    _ec.getMetaDataManager(), clr, mmd, relationType, emmd);

            if (embedded) {
                if (RelationType.isRelationSingleValued(relationType)) {
                    cmd = _ec.getMetaDataManager().getMetaDataForClass(
                            mmd.getType(), _ec.getClassLoaderResolver());
                    if (embMmd != null) {
                        embMmd = embMmd.getEmbeddedMetaData()
                                .getMemberMetaData()[mmd
                                .getAbsoluteFieldNumber()];
                    } else {
                        embMmd = mmd;
                    }
                    embMmds.add(embMmd);
                } else if (RelationType.isRelationMultiValued(relationType)) {
                    throw new NucleusUserException(
                            "Do not support the querying of embedded collection/map/array fields : "
                                    + mmd.getFullFieldName());
                }
            } else {
                embMmds.clear();
                if (relationType == RelationType.ONE_TO_MANY_UNI
                        || relationType == RelationType.ONE_TO_MANY_BI
                        || relationType == RelationType.MANY_TO_ONE_UNI
                        || relationType == RelationType.MANY_TO_ONE_BI) {
                    if (!iter.hasNext()) {
                        return name;
                    }
                    throw new NucleusUserException(
                            "Do not support _query joining to related object at "
                                    + mmd.getFullFieldName() + " in "
                                    + StringUtils.collectionToString(tuples));
                }

                if (_cc == CompilationComponent.FILTER) {
                    _filterComplete = false;
                }

                NucleusLogger.QUERY
                        .debug("Query has reference to "
                                + StringUtils.collectionToString(tuples)
                                + " and "
                                + mmd.getFullFieldName()
                                + " is not persisted into this object, so unexecutable in the datastore");
                return null;
            }
            firstTuple = false;
        }

        return null;
    }

}
