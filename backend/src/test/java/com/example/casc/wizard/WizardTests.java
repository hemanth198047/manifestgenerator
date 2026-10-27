package com.example.casc.wizard;

import static com.example.casc.TestKit.*;

import com.example.casc.app.DraftPipeline;
import com.example.casc.app.ExportResult;
import com.example.casc.domain.CasCManifest;
import com.example.casc.domain.Enums.Attestation;
import com.example.casc.domain.Enums.DataSubjectCategory;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.domain.Enums.TargetTableType;
import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import com.example.casc.ingest.SourceInput;
import com.example.casc.json.Json;
import com.example.casc.validation.Finding;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class WizardTests {
    private WizardTests() {}

    private static final WizardService SERVICE = new WizardService(new DraftPipeline());

    private static WizardState sampleState() throws Exception {
        WizardState st = new WizardState();
        st.sources.add(SourceInput.of("schema.sql", resource("/static/samples/sample-schema.sql")));
        st.sources.add(SourceInput.of("notes.md", resource("/static/samples/sample-notes.md")));
        st.sources.add(SourceInput.of("api.json", resource("/static/samples/sample-openapi.json")));
        SERVICE.ingest(st);
        return st;
    }

    private static RawTable table(WizardState st, String name) {
        return st.bundle.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static RawInterface iface(WizardState st, String name) {
        return st.bundle.interfaces().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private static void decide(WizardState st) {
        st.system.eonId = "EON-1234";
        st.system.name = "Trade System";
        st.system.databaseLogicalName = "TRADE_DB";
        st.system.databaseType = DatabaseType.ORACLE;
        WizardState.Service s1 = new WizardState.Service();
        s1.name = "TradeService";
        s1.onPrem = true;
        WizardState.Service s2 = new WizardState.Service();
        s2.name = "ReportingService";
        s2.onPrem = false;
        s2.cloudProvider = "ExampleCloud";
        st.services.addAll(List.of(s1, s2));
        WizardState.Element q = new WizardState.Element();
        q.name = "Trade Quantity";
        q.services.add("TradeService");
        WizardState.Element c = new WizardState.Element();
        c.name = "Client Identifier";
        st.elements.addAll(List.of(q, c));
    }

    public static void register() {
        test("suggestions: names are split and expanded, never applied", () -> {
            eq("Trade Quantity", NameSuggester.suggest("TRADE_QTY"));
            eq("Client Identifier", NameSuggester.suggest("clientId"));
            eq("Created At", NameSuggester.suggest("order.created_at"));
            eq("Sku", NameSuggester.suggest("items[].sku"));
            eq("Cust Name", NameSuggester.suggest("CUST_NM"));
            eq("Identifier", NameSuggester.suggest("ID"));
            eq("", NameSuggester.suggest("  "));
            eq("", NameSuggester.suggest(null));
        });

        test("pii hints are hints only", () -> {
            yes(PiiHints.hint("email_address") != null, "email");
            yes(PiiHints.hint("Home-Phone") != null, "phone");
            yes(PiiHints.hint("customer.dateOfBirth") == null || PiiHints.hint("birth_date") != null, "birth");
            eq(null, PiiHints.hint("order_id"));
            eq(null, PiiHints.hint(null));
            WizardState st = new WizardState();
            st.column("k#email").description = "x";
            eq(null, st.column("k#email").attestation);
        });

        test("system keys: documented slug policy with collision checks", () -> {
            Set<String> taken = new HashSet<>(Set.of("svc-trade-service"));
            eq("svc-trade-service-2", SystemKeys.generate("svc", "Trade Service", taken));
            eq("svc-trade-service-3", SystemKeys.generate("svc", "  Trade  Service!! ", taken));
            eq("elem-item", SystemKeys.generate("elem", "***", taken));
            eq("elem-item-2", SystemKeys.generate("elem", "", taken));
            eq("intf-list-trades", SystemKeys.generate("intf", "GET /list_trades", new HashSet<>()).replace("get-", ""));
        });

        test("assembler: only decided content is written; EON ID and name never are", () -> {
            WizardState st = sampleState();
            decide(st);
            st.asset(com.example.casc.wizard.AssetKeys.table(table(st, "orders"))).tableType = TargetTableType.TRANSACTION;
            st.asset(AssetKeys.table(table(st, "positions"))).included = false;
            String ok = AssetKeys.table(table(st, "orders"));
            WizardState.Gov g = st.column(AssetKeys.column(ok, "trade_qty"));
            g.element = "Trade Quantity";
            g.attestation = Attestation.NO;
            g.description = "Units traded (reviewed)";
            WizardState.Gov cid = st.column(AssetKeys.column(ok, "client_id"));
            cid.element = "Client Identifier";
            cid.attestation = Attestation.YES;
            cid.pii.add("Financial Account Number");
            cid.categories.add(DataSubjectCategory.CLIENT);
            st.column(AssetKeys.column(ok, "status")).lineageJson = "{\"anything\":\"x\"}";
            String list = AssetKeys.iface(iface(st, "listTrades"));
            st.asset(list).direction = InterfaceType.DISTRIBUTION;
            st.asset(list).excludedFields.add("tags[]");
            st.asset(list).services.add("TradeService");
            st.asset(AssetKeys.iface(iface(st, "createTrade"))).included = false;
            WizardState.Entity e = new WizardState.Entity();
            e.name = "trading.Trade";
            e.elements.add("Trade Quantity");
            st.entities.add(e);

            ManifestAssembler.Assembly a = SERVICE.assemble(st);
            eq(List.of(), a.findings());
            CasCManifest m = a.manifest();
            eq(2, m.logicalServices().size());
            eq(List.of("orders"), m.databases().get(0).schemas().get(0).tables().stream().map(t -> t.physicalName()).toList());
            var orders = m.databases().get(0).schemas().get(0).tables().get(0);
            eq(TargetTableType.TRANSACTION, orders.targetTableType());
            eq("Customer trade orders", orders.description());
            var qty = orders.columns().stream().filter(c -> c.physicalName().equals("trade_qty")).findFirst().orElseThrow();
            eq("Trade Quantity", qty.systemDataElement());
            eq("Units traded (reviewed)", qty.description());
            eq(Attestation.NO, qty.sensitiveDataAttestation());
            var client = orders.columns().stream().filter(c -> c.physicalName().equals("client_id")).findFirst().orElseThrow();
            eq("Financial Account Number", client.piiElements().get(0).name());
            eq(List.of(DataSubjectCategory.CLIENT), client.dataSubjectCategories());
            var created = orders.columns().stream().filter(c -> c.physicalName().equals("created_at")).findFirst().orElseThrow();
            eq(null, created.sensitiveDataAttestation());
            var status = orders.columns().stream().filter(c -> c.physicalName().equals("status")).findFirst().orElseThrow();
            eq("x", status.sourcedFromColumns().get(0).get("anything"));
            eq(1, m.interfaces().size());
            eq(List.of("tradeId", "quantity", "client.clientId", "client.name"),
                    m.interfaces().get(0).interfaceDataElements().stream().map(x -> x.physicalName()).toList());
            eq(List.of("TradeService"), m.interfaces().get(0).logicalServices());
            eq(List.of("Trade Quantity"), m.dataEntities().get(0).systemDataElements().stream().map(x -> x.name()).toList());
            String text = Json.write(com.example.casc.manifest.ManifestMapper.toTree(m));
            excludes(text, "EON-1234");
            excludes(text, "Trade System");
        });

        test("assembler: malformed lineage JSON is reported, not silently dropped; interfaces without direction are left out", () -> {
            WizardState st = sampleState();
            decide(st);
            String ok = AssetKeys.table(table(st, "orders"));
            st.column(AssetKeys.column(ok, "status")).lineageJson = "{not json";
            st.column(AssetKeys.column(ok, "created_at")).lineageJson = "[1]";
            ManifestAssembler.Assembly a = SERVICE.assemble(st);
            eq(2, a.findings().size());
            eq("LINEAGE_JSON_INVALID", a.findings().get(0).code());
            eq(null, a.manifest().interfaces());
            ExportResult r = SERVICE.review(st, "json").result();
            yes(!r.exportable(), "assembly findings block export");
            yes(r.report().errors().stream().anyMatch(f -> f.code().equals("LINEAGE_JSON_INVALID")), "reported");
        });

        test("reconciler: open decisions are listed; acknowledgements only clear acknowledgeable ones", () -> {
            WizardState st = sampleState();
            st.sources.add(SourceInput.of("people.sql", "CREATE TABLE hr.people (email_address VARCHAR(50), first_name VARCHAR(20), id INT);"));
            SERVICE.ingest(st);
            List<Reconciler.Decision> d = Reconciler.decisions(st, st.bundle, SERVICE.assemble(st).manifest());
            List<String> ids = d.stream().map(Reconciler.Decision::id).toList();
            yes(ids.contains("unreviewed-draft") && ids.contains("system-context") && ids.contains("no-services")
                    && ids.contains("no-table-type") && ids.contains("terms-unused") && ids.contains("structures-unused")
                    && ids.contains("lineage-notes"), ids.toString());
            yes(ids.stream().anyMatch(i -> i.startsWith("interface-direction:")), "interfaces need a direction");
            yes(ids.contains("attest-hint"), "email_address and first_name are hinted: " + ids);
            int before = Reconciler.open(d, st).size();
            st.acknowledged.addAll(ids);
            List<String> open = Reconciler.open(d, st);
            yes(open.size() < before, "acknowledging clears some");
            yes(open.stream().anyMatch(m -> m.contains("no SOURCING or DISTRIBUTION direction")), "direction stays open");
            yes(open.stream().anyMatch(m -> m.contains("resemble sensitive data")), "hinted attestations stay open");
            yes(open.stream().noneMatch(m -> m.contains("EON ID")), "context acknowledged");
        });

        test("reconciler: the same table in two included sources is a conflict until one is excluded", () -> {
            WizardState st = new WizardState();
            st.sources.add(SourceInput.of("one.sql", "CREATE TABLE s.t (id INT);"));
            st.sources.add(SourceInput.of("two.sql", "CREATE TABLE s.t (id INT, x INT);"));
            SERVICE.ingest(st);
            List<Reconciler.Decision> d = Reconciler.decisions(st, st.bundle, SERVICE.assemble(st).manifest());
            yes(d.stream().anyMatch(x -> x.id().equals("table-conflict:s.t") && !x.acknowledgeable()), "conflict decision");
            yes(SERVICE.review(st, "json").result().report().errors().stream().anyMatch(f -> f.code().equals("DUP_NAME")),
                    "validator also reports the duplicate");
            st.asset(AssetKeys.table(st.bundle.tables().get(1))).included = false;
            d = Reconciler.decisions(st, st.bundle, SERVICE.assemble(st).manifest());
            yes(d.stream().noneMatch(x -> x.id().startsWith("table-conflict")), "resolved by excluding one");
            yes(!SERVICE.review(st, "json").result().report().errors().stream().anyMatch(f -> f.code().equals("DUP_NAME")), "no duplicate left");
        });

        test("keys: generated on request, never over user keys, unique across kinds", () -> {
            WizardState st = sampleState();
            decide(st);
            st.services.get(0).systemKey = "my-own-key";
            WizardState.Element dup = new WizardState.Element();
            dup.name = "Trade Quantity";
            st.elements.add(dup);
            st.asset(AssetKeys.iface(iface(st, "listTrades")));
            int n = SERVICE.generateKeys(st);
            yes(n >= 4, "keys generated: " + n);
            eq("my-own-key", st.services.get(0).systemKey);
            eq("svc-reportingservice", st.services.get(1).systemKey);
            eq("elem-trade-quantity", st.elements.get(0).systemKey);
            eq("elem-trade-quantity-2", st.elements.get(2).systemKey);
            Set<String> all = new HashSet<>();
            st.services.forEach(s -> all.add(s.systemKey));
            st.elements.forEach(e -> all.add(e.systemKey));
            st.assets.values().forEach(a -> { if (!a.systemKey.isBlank()) all.add(a.systemKey); });
            eq(st.services.size() + st.elements.size() + (int) st.assets.values().stream().filter(a -> !a.systemKey.isBlank()).count(), all.size());
            eq(0, SERVICE.generateKeys(st));
            yes(SERVICE.review(st, "json").result().report().errors().stream().noneMatch(f -> f.code().equals("KEY_DUP")), "no collisions");
        });

        test("re-ingesting drops decisions for assets that disappeared and says how many", () -> {
            WizardState st = sampleState();
            String key = AssetKeys.table(table(st, "orders"));
            st.asset(key).tableType = TargetTableType.REFERENCE;
            st.column(AssetKeys.column(key, "status")).description = "x";
            eq(0, SERVICE.ingest(st));
            st.sources.removeIf(s -> s.name().equals("schema.sql"));
            yes(SERVICE.ingest(st) >= 2, "asset and column decisions dropped");
            yes(!st.assets.containsKey(key), "gone");
        });

        test("project file: round trip and strict loading", () -> {
            WizardState st = sampleState();
            decide(st);
            String ok = AssetKeys.table(table(st, "orders"));
            st.asset(ok).tableType = TargetTableType.REFERENCE;
            WizardState.Gov g = st.column(AssetKeys.column(ok, "client_id"));
            g.attestation = Attestation.YES;
            g.pii.add("Email");
            g.categories.add(DataSubjectCategory.THIRD_PARTY);
            g.lineageJson = "{\"a\":1}";
            st.asset(AssetKeys.iface(iface(st, "listTrades"))).direction = InterfaceType.SOURCING;
            st.field(AssetKeys.column(AssetKeys.iface(iface(st, "listTrades")), "tradeId")).element = "Trade Quantity";
            WizardState.Transform t = new WizardState.Transform();
            t.name = "Roll";
            t.sources.add("Trade Quantity");
            st.transformations.add(t);
            st.acknowledged.add("no-table-type");
            st.options.put("dialect", "oracle");
            String json = Json.write(ProjectFile.toTree(st));
            WizardState back = ProjectFile.fromTree(Json.parse(json));
            eq(json, Json.write(ProjectFile.toTree(back)));
            eq("EON-1234", back.system.eonId);
            eq(DatabaseType.ORACLE, back.system.databaseType);
            eq(Attestation.YES, back.columns.get(AssetKeys.column(ok, "client_id")).attestation);
            eq("", back.csrf);
            SERVICE.ingest(back);
            eq(Json.write(com.example.casc.manifest.ManifestMapper.toTree(SERVICE.assemble(st).manifest())),
                    Json.write(com.example.casc.manifest.ManifestMapper.toTree(SERVICE.assemble(back).manifest())));

            for (String bad : List.of("[]", "{\"projectVersion\":2}", "{\"projectVersion\":1,\"surprise\":1}",
                    "{\"projectVersion\":1,\"sources\":[{\"name\":1}]}", "{\"projectVersion\":1,\"options\":{\"dialect\":\"mysql\"}}",
                    "{\"projectVersion\":1,\"assets\":[{\"key\":\"k\",\"tableType\":\"transaction\"}]}",
                    "{\"projectVersion\":1,\"columns\":[{\"key\":\"k\",\"categories\":[\"Nobody\"]}]}",
                    "{\"projectVersion\":1,\"services\":[{\"name\":\"a\",\"onPrem\":\"yes\"}]}",
                    "{\"projectVersion\":1,\"assets\":[{\"included\":true}]}")) {
                throwsA(IllegalArgumentException.class, () -> ProjectFile.fromTree(Json.parse(bad)));
            }
            try {
                ProjectFile.fromTree(Json.parse("{\"projectVersion\":1,\"assets\":[{\"key\":\"k\",\"tableType\":\"transaction\"}]}"));
            } catch (IllegalArgumentException e) {
                contains(e.getMessage(), "$.assets[0].tableType");
            }
        });

        test("sessions: isolated, unguessable, expiring and capped", () -> {
            AtomicLong now = new AtomicLong(1000);
            SessionStore store = new SessionStore(3, 5000, now::get);
            SessionStore.Session a = store.create();
            SessionStore.Session b = store.create();
            yes(a.id.length() >= 43 && !a.id.equals(b.id), "ids are long and unique");
            yes(!a.state.csrf.equals(b.state.csrf) && a.state.csrf.length() >= 30, "csrf tokens differ");
            a.state.system.name = "A";
            eq("", store.get(b.id).orElseThrow().state.system.name);
            eq("A", store.get(a.id).orElseThrow().state.system.name);
            yes(store.get("nope").isEmpty() && store.get(null).isEmpty(), "unknown ids");
            now.addAndGet(4000);
            store.get(a.id);                       // touching keeps a alive
            now.addAndGet(2000);
            yes(store.get(a.id).isPresent(), "touched session survives");
            yes(store.get(b.id).isEmpty(), "idle session expired");
            SessionStore.Session c = store.create();
            SessionStore.Session d = store.create();
            SessionStore.Session e = store.create();
            eq(3, store.size());
            yes(store.get(a.id).isEmpty(), "oldest session evicted at the cap");
            yes(store.get(c.id).isPresent() && store.get(d.id).isPresent() && store.get(e.id).isPresent(), "newer sessions kept");
            store.remove(c.id);
            eq(2, store.size());
        });
    }
}
