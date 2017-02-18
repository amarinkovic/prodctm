package pro.documentum.persistence.jpa.query;

import static org.hamcrest.Matchers.endsWith;

import org.junit.Test;

import pro.documentum.model.jpa.user.DmUser;

/**
 * @author Andrey B. Panfilov <andrey@panfilov.tel>
 */
public class LowerQueryTest extends AbstractQueryTest {

    @Test
    public void testLower1() throws Exception {
        String q = str(jpql(DmUser.class, "LOWER(userName) = 'dmadmin'"));
        assertThat(q, endsWith("WHERE LOWER(this.user_name)='dmadmin'"));
    }

    @Test
    public void testLower2() throws Exception {
        String q = str(jpql(DmUser.class, "userName.toLowerCase() = 'dmadmin'"));
        assertThat(q, endsWith("WHERE LOWER(this.user_name)='dmadmin'"));
    }

    @Test
    public void testLower3() throws Exception {
        String q = str(jpql(DmUser.class, "userName = LOWER('dmadmin')"));
        assertThat(q, endsWith("WHERE this.user_name=LOWER('dmadmin')"));
    }

}
