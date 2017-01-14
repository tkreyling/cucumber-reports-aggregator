package kreyling.cragg;

import static kreyling.cragg.Main.TestReport.countDirectlySuccessionalFailures;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class DetermineSystemFailureTest {

    @Test
    public void testSuccessionalFailures() {
        List<String> status = Arrays.asList("Failed", "Failed", "Passed", "Passed", "Failed");

        int directlySuccessionalFailures = countDirectlySuccessionalFailures(status);

        assertThat(directlySuccessionalFailures, is(2));
    }

}
