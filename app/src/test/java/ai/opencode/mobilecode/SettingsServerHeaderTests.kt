package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.expect
import org.junit.Test

/// The Settings card names the machine: a host name's first label, or the
/// whole address for an IP or a single-label host.
class SettingsServerHeaderTests {
    @Test
    fun aHostNameShowsItsFirstLabel() {
        expect(machineName("basebook.husky-tilapia.ts.net:4102") == "basebook")
        expect(machineName("box.example.com") == "box")
    }

    @Test
    fun anAddressOrASingleLabelShowsWhole() {
        expect(machineName("127.0.0.1:4110") == "127.0.0.1:4110")
        expect(machineName("localhost:4096") == "localhost:4096")
    }
}
