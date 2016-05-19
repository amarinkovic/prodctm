package pro.documentum.jdo.query;

import org.datanucleus.query.expression.Expression;
import org.datanucleus.query.expression.ExpressionEvaluator;
import org.datanucleus.query.expression.PrimaryExpression;

import pro.documentum.jdo.query.expression.DQLExpression;

/**
 * @author Andrey B. Panfilov <andrey@panfilov.tel>
 */
public interface IDQLEvaluator extends ExpressionEvaluator {

    DQLExpression popExpression();

    Object processPrimaryExpression(PrimaryExpression expr);

    DQLExpression processLiteralOrParameter(Expression expression);

}
