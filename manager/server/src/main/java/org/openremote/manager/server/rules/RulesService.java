/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.server.rules;

import elemental.json.JsonObject;
import org.apache.camel.builder.RouteBuilder;
import org.drools.core.base.evaluators.TimeIntervalParser;
import org.openremote.container.Container;
import org.openremote.container.ContainerService;
import org.openremote.container.message.MessageBrokerSetupService;
import org.openremote.container.persistence.PersistenceEvent;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.manager.server.asset.AssetProcessingService;
import org.openremote.manager.server.asset.AssetStorageService;
import org.openremote.manager.server.asset.ServerAsset;
import org.openremote.manager.server.notification.NotificationService;
import org.openremote.manager.server.security.ManagerIdentityService;
import org.openremote.manager.server.concurrent.ManagerExecutorService;
import org.openremote.manager.shared.rules.AssetRuleset;
import org.openremote.manager.shared.rules.GlobalRuleset;
import org.openremote.manager.shared.rules.Ruleset;
import org.openremote.manager.shared.rules.TenantRuleset;
import org.openremote.manager.shared.security.Tenant;
import org.openremote.model.AbstractValueTimestampHolder;
import org.openremote.model.Pair;
import org.openremote.model.asset.*;
import org.openremote.model.util.JsonUtil;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.openremote.container.persistence.PersistenceEvent.PERSISTENCE_TOPIC;
import static org.openremote.container.util.MapAccess.getString;
import static org.openremote.manager.server.asset.AssetPredicates.isPersistenceEventForEntityType;
import static org.openremote.model.asset.AssetAttribute.Functions.getAssetAttributesFromJson;

/**
 * Responsible for creating Drools knowledge sessions for the rulesets
 * and processing {@link AssetState}  and {@link AssetEvent} messages.
 * <p>
 * Each message is processed in the following order:
 * <ol>
 * <li>Global Rulesets</li>
 * <li>Tenant Rulesets</li>
 * <li>Asset Rulesets (in hierarchical order from oldest ancestor down - ordering of
 * asset rulesets with same parent asset are not guaranteed also processing order of rulesets
 * with the same scope is not guaranteed)</li>
 * </ol>
 */
public class RulesService extends RouteBuilder implements ContainerService, Consumer<AssetState> {

    private static final Logger LOG = Logger.getLogger(RulesService.class.getName());

    public static final String RULE_EVENT_EXPIRES = "RULE_EVENT_EXPIRES";
    public static final String RULE_EVENT_EXPIRES_DEFAULT = "1h";

    protected ManagerExecutorService executorService;
    protected PersistenceService persistenceService;
    protected RulesetStorageService rulesetStorageService;
    protected ManagerIdentityService identityService;
    protected AssetStorageService assetStorageService;
    protected NotificationService notificationService;
    protected AssetProcessingService assetProcessingService;
    protected RulesEngine<GlobalRuleset> globalDeployment;
    protected final Map<String, RulesEngine<TenantRuleset>> tenantDeployments = new HashMap<>();
    protected final Map<String, RulesEngine<AssetRuleset>> assetDeployments = new HashMap<>();
    protected String[] activeTenantIds;

    // Keep global list of asset states that have been pushed to any engines
    // The objects are already in memory inside the rule engines but keeping them
    // here means we can quickly insert facts into newly started engines
    protected List<AssetState> assetStates = new ArrayList<>();

    protected String configEventExpires;

    @Override
    public void init(Container container) throws Exception {
        executorService = container.getService(ManagerExecutorService.class);
        persistenceService = container.getService(PersistenceService.class);
        rulesetStorageService = container.getService(RulesetStorageService.class);
        identityService = container.getService(ManagerIdentityService.class);
        notificationService = container.getService(NotificationService.class);
        assetStorageService = container.getService(AssetStorageService.class);
        assetProcessingService = container.getService(AssetProcessingService.class);
        container.getService(MessageBrokerSetupService.class).getContext().addRoutes(this);

        configEventExpires = getString(container.getConfig(), RULE_EVENT_EXPIRES, RULE_EVENT_EXPIRES_DEFAULT);
    }

    @Override
    public void configure() throws Exception {
        // If any ruleset was modified in the database then check its' status and undeploy, deploy, or update it
        from(PERSISTENCE_TOPIC)
            .filter(isPersistenceEventForEntityType(Ruleset.class))
            .process(exchange -> {
                PersistenceEvent persistenceEvent = exchange.getIn().getBody(PersistenceEvent.class);
                processRulesetChange((Ruleset) persistenceEvent.getEntity(), persistenceEvent.getCause());
            });

        // If any tenant was modified in the database then check its' status and undeploy, deploy or update any
        // associated rulesets
        from(PERSISTENCE_TOPIC)
            .filter(isPersistenceEventForEntityType(Tenant.class))
            .process(exchange -> {
                PersistenceEvent persistenceEvent = exchange.getIn().getBody(PersistenceEvent.class);
                Tenant tenant = (Tenant) persistenceEvent.getEntity();
                processTenantChange(tenant, persistenceEvent.getCause());
            });

        // If any asset was modified in the database, detect changed attributes
        from(PERSISTENCE_TOPIC)
            .filter(isPersistenceEventForEntityType(Asset.class))
            .process(exchange -> {
                PersistenceEvent persistenceEvent = exchange.getIn().getBody(PersistenceEvent.class);
                final ServerAsset eventAsset = (ServerAsset) persistenceEvent.getEntity();
                processAssetChange(eventAsset, persistenceEvent);
            });
    }

    @Override
    public void start(Container container) throws Exception {
        LOG.info("Deploying global rulesets");
        rulesetStorageService.findEnabledGlobalRulesets().forEach(this::deployGlobalRuleset);

        LOG.info("Deploying tenant rulesets");
        activeTenantIds = identityService.getActiveTenantIds();
        rulesetStorageService.findEnabledTenantRulesets()
            .stream()
            .filter(rd ->
                Arrays.stream(activeTenantIds)
                    .anyMatch(tenantId -> rd.getRealmId().equals(tenantId))
            ).forEach(this::deployTenantRuleset);

        LOG.info("Deploying asset rulesets");
        // Group by asset ID then tenant and check tenant is enabled
        deployAssetRulesets(rulesetStorageService.findEnabledAssetRulesets());

        LOG.info("Loading all assets with fact attributes to initialize state of rules engines");
        Stream<Pair<ServerAsset, Stream<AssetAttribute>>> assetRuleAttributes = findRuleStateAttributes();

        // Push each rule attribute as an asset update through the rule engine chain
        // that will ensure the insert only happens to the engines in scope
        assetRuleAttributes
            .forEach(pair -> {
                ServerAsset asset = pair.key;
                pair.value.forEach(ruleAttribute -> {
                    AssetState assetState = new AssetState(asset, ruleAttribute);
                    // Set the status to completed already so rules cannot interfere with this initial insert
                    assetState.setProcessingStatus(AssetState.ProcessingStatus.COMPLETED);
                    LOG.fine("Inserting initial rules engine state: " + assetState);
                    updateAssetState(assetState, true);
                });
            });
    }

    @Override
    public void stop(Container container) throws Exception {
        synchronized (assetDeployments) {
            assetDeployments.forEach((assetId, deployment) -> deployment.stop());
            assetDeployments.clear();
        }

        synchronized (tenantDeployments) {
            tenantDeployments.forEach((realm, deployment) -> deployment.stop());
            tenantDeployments.clear();
        }

        if (globalDeployment != null) {
            globalDeployment.stop();
            globalDeployment = null;
        }
    }

    @Override
    public void accept(AssetState assetState) {
        if (assetState.getAttribute().isRuleState()) {
            updateAssetState(assetState, false);
        } else if (assetState.getAttribute().isRuleEvent()) {
            process(new AssetEvent(assetState));
        } else {
            LOG.finest("Ignoring asset state as attribute is not a rules state or event: " + assetState);
        }
    }

    protected synchronized void processTenantChange(Tenant tenant, PersistenceEvent.Cause cause) {
        // Check if enabled status has changed
        boolean wasEnabled = Arrays.asList(activeTenantIds).contains(tenant.getId());
        boolean isEnabled = tenant.getEnabled() && cause != PersistenceEvent.Cause.DELETE;
        activeTenantIds = identityService.getActiveTenantIds();

        if (wasEnabled == isEnabled) {
            // Nothing to do here
            return;
        }

        if (wasEnabled) {
            // Remove tenant deployment for this tenant if it exists
            RulesEngine<TenantRuleset> tenantDeployment = tenantDeployments.get(tenant.getId());
            if (tenantDeployment != null) {
                // Use a copy of the list to avoid concurrent modification problems in retract
                new ArrayList<>(Arrays.asList(tenantDeployment.getAllRulesets()))
                    .forEach(this::undeployTenantRuleset);
            }

            // Remove any asset deployments for assets in this realm
            // Use a copy of the list to avoid concurrent modification problems in retract
            new ArrayList<>(assetDeployments.values()).stream().flatMap(
                assetRulesEngine -> Arrays.stream(assetRulesEngine.getAllRulesets())
            ).filter(ruleset -> ruleset.getRealmId().equals(tenant.getId()))
                .forEach(this::undeployAssetRuleset);

        } else {
            // Create tenant deployment for this tenant if it has any rulesets
            rulesetStorageService
                .findEnabledTenantRulesets(tenant.getId())
                .forEach(this::deployTenantRuleset);

            // Create any asset deployments for assets in this realm that have rulesets
            deployAssetRulesets(rulesetStorageService.findEnabledAssetRulesets(tenant.getId()));
        }
    }

    protected synchronized void processAssetChange(ServerAsset asset, PersistenceEvent persistenceEvent) {

        // We must load the asset from database (only when required), as the
        // persistence event might not contain a completely loaded asset
        BiFunction<Asset, AssetAttribute, AssetState> buildAssetState = (loadedAsset, attribute) ->
            new AssetState(loadedAsset,
                new AssetAttribute(Optional.of(loadedAsset.getId()),
                    attribute.getName(),
                    attribute.getJsonObject())
            );
        Asset loadedAsset = null;

        switch (persistenceEvent.getCause()) {
            case INSERT:

                // New asset has been created so get attributes that have RULE_STATE meta
                List<AssetAttribute> ruleAttributes =
                    asset.getAttributeStream().filter(AssetAttribute::isRuleState).collect(Collectors.toList());

                // Fully load the asset
                loadedAsset = assetStorageService.find(asset.getId(), true);
                Asset finalLoadedAsset = loadedAsset;

                ruleAttributes.forEach(attribute -> {
                    AssetState assetState = buildAssetState.apply(finalLoadedAsset, attribute);
                    // Set the status to completed already so rules cannot interfere with this initial insert
                    assetState.setProcessingStatus(AssetState.ProcessingStatus.COMPLETED);
                    LOG.fine("Asset was persisted (" + persistenceEvent.getCause() + "), inserting fact: " + assetState);
                    updateAssetState(assetState, true);
                });
                break;

            case UPDATE:

                int attributesIndex = Arrays.asList(persistenceEvent.getPropertyNames()).indexOf("attributes");
                if (attributesIndex < 0) {
                    return;
                }

                // Fully load the asset
                loadedAsset = assetStorageService.find(asset.getId(), true);
                Asset finalLoadedAsset1 = loadedAsset;

                // Attributes have possibly changed so need to compare old and new state to determine
                // which facts to retract and which to insert
                List<AssetAttribute> oldFactAttributes =
                    getAssetAttributesFromJson(asset.getId()).apply(
                        (JsonObject) persistenceEvent.getPreviousState()[attributesIndex]
                    ).filter(AssetAttribute::isRuleState)
                        .collect(Collectors.toList());

                List<AssetAttribute> newFactAttributes =
                    getAssetAttributesFromJson(asset.getId()).apply(
                        (JsonObject) persistenceEvent.getCurrentState()[attributesIndex]
                    ).filter(AssetAttribute::isRuleState)
                        .collect(Collectors.toList());

                // Compare attributes by JSON value
                // Retract facts for attributes that are in oldFactAttributes but not in newFactAttributes
                Stream<AssetAttribute> processAttributes = oldFactAttributes
                    .stream()
                    .filter(oldFactAttribute -> newFactAttributes
                        .stream()
                        .noneMatch(newFactAttribute -> JsonUtil.equals( // Ignore the timestamp in comparison
                            oldFactAttribute.getJsonObject(),
                            newFactAttribute.getJsonObject(),
                            Collections.singletonList(AbstractValueTimestampHolder.VALUE_TIMESTAMP_FIELD_NAME))
                        )
                    );


                processAttributes.forEach(obsoleteFactAttribute -> {
                    AssetState update = buildAssetState.apply(finalLoadedAsset1, obsoleteFactAttribute);
                    LOG.fine("Asset was persisted (" + persistenceEvent.getCause() + "), retracting fact: " + update);
                    retractAssetState(update);
                });

                // Insert facts for attributes that are in newFactAttributes but not in oldFactAttributes
                processAttributes = newFactAttributes
                    .stream()
                    .filter(newFactAttribute -> oldFactAttributes
                        .stream()
                        .noneMatch(oldFactAttribute ->
                            JsonUtil.equals( // Ignore the timestamp in comparison
                                oldFactAttribute.getJsonObject(),
                                newFactAttribute.getJsonObject(),
                                Collections.singletonList(AbstractValueTimestampHolder.VALUE_TIMESTAMP_FIELD_NAME)
                            )
                        )
                    );

                processAttributes.forEach(newFactAttribute -> {
                    AssetState assetState = buildAssetState.apply(finalLoadedAsset1, newFactAttribute);
                    // Set the status to completed already so rules cannot interfere with this initial insert
                    assetState.setProcessingStatus(AssetState.ProcessingStatus.COMPLETED);
                    LOG.fine("Asset was persisted (" + persistenceEvent.getCause() + "), inserting fact: " + assetState);
                    updateAssetState(assetState, true);
                });

                break;

            case DELETE:
                // Retract any facts that were associated with this asset
                asset.getAttributeStream()
                    .filter(AssetAttribute::isRuleState)
                    .forEach(attribute -> {
                        // We can't load the asset again (it was deleted), so don't use buildAssetState() and
                        // hope that the path of the event asset has been loaded before deletion
                        AssetState assetState = new AssetState(asset, attribute);
                        LOG.fine("Asset was persisted (" + persistenceEvent.getCause() + "), retracting fact: " + assetState);
                        retractAssetState(assetState);
                    });
                break;
        }
    }

    protected synchronized void processRulesetChange(Ruleset ruleset, PersistenceEvent.Cause cause) {
        if (cause == PersistenceEvent.Cause.DELETE || !ruleset.isEnabled()) {
            if (ruleset instanceof GlobalRuleset) {
                undeployGlobalRuleset((GlobalRuleset) ruleset);
            } else if (ruleset instanceof TenantRuleset) {
                undeployTenantRuleset((TenantRuleset) ruleset);
            } else if (ruleset instanceof AssetRuleset) {
                undeployAssetRuleset((AssetRuleset) ruleset);
            }
        } else {
            if (ruleset instanceof GlobalRuleset) {

                RulesEngine newEngine = deployGlobalRuleset((GlobalRuleset) ruleset);
                if (newEngine != null) {
                    // Push all existing facts into the engine
                    assetStates.forEach(newEngine::updateAssetState);
                }

            } else if (ruleset instanceof TenantRuleset) {

                RulesEngine newEngine = deployTenantRuleset((TenantRuleset) ruleset);
                if (newEngine != null) {
                    // Push all existing facts for this tenant into the engine
                    assetStates.forEach(assetState -> {
                        if (assetState.getRealmId().equals(((TenantRuleset) ruleset).getRealmId())) {
                            newEngine.updateAssetState(assetState);
                        }
                    });
                }

            } else if (ruleset instanceof AssetRuleset) {

                // Must reload from the database, the ruleset might not be completely hydrated on INSERT or UPDATE
                AssetRuleset assetRuleset = rulesetStorageService.findEnabledAssetRuleset(ruleset.getId());
                RulesEngine newEngine = deployAssetRuleset(assetRuleset);
                if (newEngine != null) {
                    // Push all existing facts for this asset (and it's children into the engine)
                    getAssetStatesInScope(((AssetRuleset) ruleset).getAssetId())
                        .forEach(newEngine::updateAssetState);

                }
            }
        }
    }

    /**
     * Deploy the ruleset into the global engine creating the engine if necessary; if the engine was created then it
     * is returned from the method.
     */
    protected synchronized RulesEngine<GlobalRuleset> deployGlobalRuleset(GlobalRuleset ruleset) {
        boolean created = globalDeployment == null;

        // Global rules have access to everything in the system
        if (globalDeployment == null) {
            globalDeployment = new RulesEngine<>(
                executorService,
                assetStorageService,
                notificationService,
                assetProcessingService,
                GlobalRuleset.class,
                "GLOBAL"
            );
        }

        globalDeployment.addRuleset(ruleset);
        return created ? globalDeployment : null;
    }

    protected synchronized void undeployGlobalRuleset(GlobalRuleset ruleset) {
        if (globalDeployment == null) {
            return;
        }

        globalDeployment.removeRuleset(ruleset);

        if (globalDeployment.isEmpty()) {
            globalDeployment = null;
        }
    }

    protected synchronized RulesEngine<TenantRuleset> deployTenantRuleset(TenantRuleset ruleset) {
        final boolean[] created = {false};

        // Look for existing deployment for this tenant
        RulesEngine<TenantRuleset> deployment = tenantDeployments
            .computeIfAbsent(ruleset.getRealmId(), (realmId) -> {
                created[0] = true;
                return new RulesEngine<>(
                    executorService,
                    assetStorageService,
                    notificationService,
                    assetProcessingService,
                    TenantRuleset.class,
                    realmId
                );
            });

        deployment.addRuleset(ruleset);

        return created[0] ? deployment : null;
    }

    protected synchronized void undeployTenantRuleset(TenantRuleset ruleset) {
        RulesEngine<TenantRuleset> deployment = tenantDeployments.get(ruleset.getRealmId());
        if (deployment == null) {
            return;
        }

        deployment.removeRuleset(ruleset);

        if (deployment.isEmpty()) {
            tenantDeployments.remove(ruleset.getRealmId());
        }
    }

    protected void deployAssetRulesets(List<AssetRuleset> rulesets) {
        rulesets
            .stream()
            .collect(Collectors.groupingBy(AssetRuleset::getAssetId))
            .entrySet()
            .stream()
            .map(es ->
                new Pair<>(assetStorageService.find(es.getKey(), true), es.getValue())
            )
            .filter(assetAndRules -> assetAndRules.key != null)
            .collect(Collectors.groupingBy(assetAndRules -> assetAndRules.key.getRealmId()))
            .entrySet()
            .stream()
            .filter(es -> Arrays
                .stream(activeTenantIds)
                .anyMatch(at -> es.getKey().equals(at)))
            .forEach(es -> {
                List<Pair<ServerAsset, List<AssetRuleset>>> tenantAssetAndRules = es.getValue();

                // RT: Not sure we need ordering here for starting engines so removing it
                // Order rulesets by asset hierarchy within this tenant
                tenantAssetAndRules.stream()
                    //.sorted(Comparator.comparingInt(item -> item.key.getPath().length))
                    .flatMap(assetAndRules -> assetAndRules.value.stream())
                    .forEach(this::deployAssetRuleset);
            });
    }

    protected synchronized RulesEngine<AssetRuleset> deployAssetRuleset(AssetRuleset ruleset) {
        final boolean[] created = {false};

        // Look for existing deployment for this asset
        RulesEngine<AssetRuleset> deployment = assetDeployments
            .computeIfAbsent(ruleset.getAssetId(), (assetId) -> {
                created[0] = true;
                return new RulesEngine<>(
                    executorService,
                    assetStorageService,
                    notificationService,
                    assetProcessingService,
                    AssetRuleset.class,
                    assetId
                );
            });

        deployment.addRuleset(ruleset);
        return created[0] ? deployment : null;
    }

    protected synchronized void undeployAssetRuleset(AssetRuleset ruleset) {
        RulesEngine<AssetRuleset> deployment = assetDeployments.get(ruleset.getAssetId());
        if (deployment == null) {
            return;
        }

        deployment.removeRuleset(ruleset);

        if (deployment.isEmpty()) {
            assetDeployments.remove(ruleset.getAssetId());
        }
    }

    protected synchronized void process(AssetEvent assetEvent) {
        // TODO: implement rules processing error state handling

        // Get the chain of rule engines that we need to pass through
        List<RulesEngine> rulesEngines = getEnginesInScope(assetEvent.getRealmId(), assetEvent.getPathFromRoot());

        if (rulesEngines.size() == 0) {
            LOG.fine("Ignoring asset event as there are no matching rules engines: " + assetEvent);
        }

        // Check that all engines in the scope are not in ERROR state
        if (rulesEngines.stream().anyMatch(RulesEngine::isError)) {
            LOG.severe("At least one rule engine is in an error state so cannot process event:" + assetEvent);
            return;
        }

        // Pass through each engine and try and insert the fact
        for (RulesEngine deployment : rulesEngines) {

            // Any exceptions in rule RHS will bubble up and the engine would be marked as in ERROR so future
            // updates will be blocked
            String eventExpires = assetEvent.getExpires().orElse(configEventExpires);
            long expirationOffset = TimeIntervalParser.parseSingle(eventExpires);

            deployment.insertAssetEvent(expirationOffset, assetEvent);
        }
    }

    protected synchronized void updateAssetState(AssetState assetState, boolean skipStatusCheck) {
        // TODO: implement rules processing error state handling

        // Get the chain of rule engines that we need to pass through
        List<RulesEngine> rulesEngines = getEnginesInScope(assetState.getRealmId(), assetState.getPathFromRoot());

        if (rulesEngines.size() == 0) {
            LOG.fine("Ignoring as there are no matching rules engine: " + assetState);
        }

        if (!skipStatusCheck) {
            // Check that all engines in the scope are not in ERROR state
            for (RulesEngine rulesEngine : rulesEngines) {
                if (rulesEngine.isError()) {
                    assetState.setProcessingStatus(AssetState.ProcessingStatus.ERROR);
                    assetState.setError(rulesEngine.getError());
                    return;
                }
            }
        }

        // Remove any stale fact that has equality with this new one or the attribute ref matches
        // (this is what rules deployment does also)
        if (!assetStates.remove(assetState)) {
            assetStates.removeIf(storedAssetState -> storedAssetState.attributeRefsEqual(assetState));
        }
        // Insert the new fact
        assetStates.add(assetState);

        // Pass through each engine and try and insert the fact
        for (RulesEngine deployment : rulesEngines) {
            LOG.fine("@@@ On " + deployment + ", updating: " + assetState);
            deployment.updateAssetState(assetState);
        }
    }

    protected void retractAssetState(AssetState assetState) {
        // Get the chain of rule engines that we need to pass through
        List<RulesEngine> rulesEngines = getEnginesInScope(assetState.getRealmId(), assetState.getPathFromRoot());

        if (!assetStates.remove(assetState)) {
            assetStates.removeIf(storedAssetState -> storedAssetState.attributeRefsEqual(assetState));
        }

        if (rulesEngines.size() == 0) {
            LOG.fine("Ignoring as there are no matching rules engines: " + assetState);
        }

        // Pass through each engine and retract this fact
        for (RulesEngine deployment : rulesEngines) {
            LOG.fine("@@@ On " + deployment + ", retracting: " + assetState);
            deployment.retractAssetState(assetState);
        }
    }

    protected List<AssetState> getAssetStatesInScope(String assetId) {
        return assetStates
            .stream()
            .filter(assetState -> Arrays.asList(assetState.getPathFromRoot()).contains(assetId))
            .collect(Collectors.toList());
    }

    protected List<RulesEngine> getEnginesInScope(String assetRealmId, String[] assetPath) {
        List<RulesEngine> rulesEngines = new ArrayList<>();

        // Add global engine (if it exists)
        if (globalDeployment != null) {
            rulesEngines.add(globalDeployment);
        }

        // Add tenant engine (if it exists)
        RulesEngine tenantDeployment = tenantDeployments.get(assetRealmId);

        if (tenantDeployment != null) {
            rulesEngines.add(tenantDeployment);
        }

        // Add asset engines
        // Iterate through asset hierarchy using asset IDs from getPath
        for (String assetId : assetPath) {
            RulesEngine assetDeployment = assetDeployments.get(assetId);
            if (assetDeployment != null) {
                rulesEngines.add(assetDeployment);
            }
        }

        return rulesEngines;
    }

    protected Stream<Pair<ServerAsset, Stream<AssetAttribute>>> findRuleStateAttributes() {
        List<ServerAsset> assets = assetStorageService.findAll(
            new AssetQuery()
                .select(new AssetQuery.Select(true))
                .attributeMeta(
                    new AssetQuery.AttributeMetaPredicate(
                        AssetMeta.RULE_STATE,
                        new AssetQuery.BooleanPredicate(true))
                ));

        return assets.stream()
            .map((ServerAsset asset) ->
                new Pair<>(asset, asset.getAttributeStream().filter(AssetAttribute::isRuleState))
            );
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{}";
    }
}