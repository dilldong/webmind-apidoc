(function ($, api, history) {
    "use strict";

    const defaultMediaTypes = ["application/json", "application/x-www-form-urlencoded", "multipart/form-data", "text/plain", "application/xml"];
    const untaggedLabel = "未分组";
    const responsePreviewSize = 256 * 1024;
    const dateFormat = new Intl.DateTimeFormat("zh-CN", {
        month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
    });

    $(function () {
        const specUrl = new URL("./v3/api-docs", document.baseURI);
        const scope = specUrl.pathname;
        const groupStates = new Map();
        const searchGroupStates = new Map();
        const globalHeaderKey = `webmind-apidoc:global-header:${scope}`;
        const globalHeaderDialog = $("#global-header-dialog")[0];
        let globalHeaders = readGlobalHeaders();
        let definition = {};
        let credentials = api.credentialsFor(definition);
        let operations = [];
        let currentOperation;
        let selectedRecord;
        let shownResponse;
        let records = [];
        let controller;
        let selection = 0;
        let toastTimer;

        $("#retry-load").on("click", loadDocument);
        $("#endpoint-search").on("input", () => {
            searchGroupStates.clear();
            renderOperations();
        });
        $("[data-endpoint-groups]").on("click", function () { setEndpointGroups(this.dataset.endpointGroups === "expand"); });
        $("[data-navigation]").on("click", function () { switchNavigation($(this).data("navigation")); });
        $("[data-request-tab]").on("click", function () { switchPanel("request", $(this).data("request-tab")); });
        $("[data-response-tab]").on("click", function () { switchPanel("response", $(this).data("response-tab")); });
        $("#request-form").on("input change", ".field", refreshUrl);
        $("#request-form").on("submit", sendRequest);
        $("#server-select").on("change", fillGlobalHeaders);
        $("#configure-global-header").on("click", editGlobalHeaders);
        $("#global-header-form").on("submit", saveGlobalHeaders);
        $("#add-global-header").on("click", () => addRow($("#global-header-fields"), "header", undefined, true));
        $("#cancel-global-header").on("click", () => globalHeaderDialog.close());
        $("#clear-global-header").on("click", () => updateGlobalHeaders([]));
        $(globalHeaderDialog).on("close", () => $("#global-header-fields").empty());
        $("#cancel-request").on("click", () => controller?.abort());
        $("#add-query").on("click", () => addRow($("#query-fields"), "query"));
        $("#add-header").on("click", () => addRow($("#extra-header-fields"), "header"));
        $("#body-type").on("change", updateBodyHint);
        $("#insert-example").on("click", insertBodyExample);
        $("#copy-response").on("click", copyResponse);
        $("#download-response").on("click", downloadResponse);
        $("#delete-history").on("click", deleteSelectedRecord);
        $("#clear-history").on("click", clearHistory);

        fillGlobalHeaders();
        loadDocument();

        async function loadDocument() {
            setLoadMessage("正在加载 OpenAPI 文档…");
            try {
                definition = await $.getJSON(specUrl.href);
                operations = api.operationList(definition, specUrl);
                credentials = api.credentialsFor(definition);
                const title = definition.info?.title || "";
                const version = definition.info?.version;
                const displayTitle = version ? `${title} · ${version}` : title;
                $("#document-title").text(displayTitle).prop("hidden", !displayTitle);
                $("#spec-version").text(`OpenAPI ${definition.openapi}`).prop("hidden", false);
                $("#operation-count").text(operations.length).prop("hidden", false);
                if (operations.length) selectOperation(operations[0]);
                else {
                    setLoadMessage("文档中暂无接口。");
                    renderOperations();
                }
            } catch (error) {
                const message = error instanceof Error ? error.message
                    : (error.status ? `无法加载接口文档（HTTP ${error.status}）。` : "无法连接文档接口。");
                setLoadMessage(message, true);
                $("#endpoint-empty").text("接口列表暂不可用").prop("hidden", false);
            }
        }

        function setLoadMessage(message, retry = false) {
            $("#operation-workspace").prop("hidden", true);
            $("#load-state").prop("hidden", false);
            $("#load-message").text(message);
            $("#retry-load").prop("hidden", !retry);
        }

        function renderOperations() {
            const search = $("#endpoint-search").val().trim().toLowerCase();
            const states = search ? searchGroupStates : groupStates;
            const visible = operations.filter(operation => {
                const tags = operation.tags.length ? operation.tags : [untaggedLabel];
                return [operation.method, operation.path, operation.title, ...tags].join(" ").toLowerCase().includes(search);
            });
            const list = $("#endpoint-list").empty();
            for (const [tag, entries] of api.groupOperations(definition, visible)) {
                const group = $("<details>", { class: "endpoint-group" })
                    .data("tag", tag).prop("open", states.get(tag) ?? Boolean(search)).appendTo(list);
                $("<summary>", { class: "endpoint-group-label" }).append(
                    $("<span>").text(tag ?? untaggedLabel),
                    $("<span>", { class: "count" }).text(entries.length)
                ).on("click", () => states.set(tag, !group[0].open)).appendTo(group);
                const items = $("<div>", { class: "endpoint-group-items" }).appendTo(group);
                for (const operation of entries) items.append(operationButton(operation));
            }
            $("#endpoint-empty").text(search ? "未找到匹配的接口" : "文档中暂无接口").prop("hidden", visible.length > 0);
            $("#endpoint-group-actions").prop("hidden", !visible.length);
        }

        function setEndpointGroups(open) {
            const states = $("#endpoint-search").val().trim() ? searchGroupStates : groupStates;
            $("#endpoint-list > details").each(function () {
                states.set($(this).data("tag"), open);
                this.open = open;
            });
        }

        function operationButton(operation) {
            const active = operation === currentOperation;
            const button = $("<button>", { type: "button", class: "endpoint-item", title: operation.title })
                .toggleClass("is-active", active)
                .attr("aria-current", active ? "true" : "false")
                .append(entryLine(operation.method, operation.path))
                .on("click", () => selectOperation(operation));
            if (operation.title !== operation.path) {
                $("<p>", { class: "entry-summary" }).text(operation.title).appendTo(button);
            }
            return button;
        }

        function entryLine(method, path) {
            return $("<div>", { class: "endpoint-line" }).append(
                $("<span>", { class: "method-badge", "data-method": method }).text(method),
                $("<code>", { class: "entry-path" }).text(path)
            );
        }

        function selectOperation(operation, record) {
            selection++;
            currentOperation = operation;
            selectedRecord = record;
            $("#operation-workspace").prop("hidden", false);
            $("#load-state").prop("hidden", true);
            $("#operation-method").attr("data-method", operation.method).text(operation.method);
            $("#operation-path").text(operation.path);
            $("#operation-title").text(operation.title);
            $("#operation-description").text(operation.description || "").prop("hidden", !operation.description);
            $("#restore-notice").text(operation.archived ? "该接口已从文档移除，可查看这次请求的记录。" : "已回填历史请求。认证参数需重新输入。")
                .prop("hidden", !record);
            renderRequest(operation, record?.draft);
            renderResponseDocs(operation);
            renderOperations();
            renderHistory();
            switchPanel("response", "content");
            renderResponse(record);
            updateSendButton();
        }

        function renderRequest(operation, draft) {
            const servers = [...operation.servers];
            if (draft?.server && !servers.some(server => server.url === draft.server)) {
                servers.push({ url: draft.server, label: "历史服务地址" });
            }
            const server = $("#server-select").empty();
            for (const entry of servers) {
                const label = entry.label ? `${entry.label} · ${entry.url}` : entry.url;
                $("<option>").val(entry.url).text(label).appendTo(server);
            }
            server.val(draft?.server || servers[0].url);

            createParameterGroup($("#parameter-fields"), "路径参数", operation.parameters.filter(value => value.in === "path"), draft);
            createParameterGroup($("#parameter-fields"), "查询参数", operation.parameters.filter(value => value.in === "query"), draft, true);
            createParameterGroup($("#header-fields"), "请求头", operation.parameters.filter(value => value.in === "header"), draft);
            if (!$("#parameter-fields").children().length) {
                $("<p>", { class: "field-help" }).text("文档未声明路径或查询参数。").appendTo("#parameter-fields");
            }
            const cookies = operation.parameters.filter(value => value.in === "cookie");
            const cookieMessage = `Cookie 由浏览器管理。文档声明了 ${cookies.map(value => value.name).join("、")}。`;
            $("#cookie-notice").text(cookieMessage).prop("hidden", !cookies.length);
            $("#query-fields, #extra-header-fields").empty();
            (draft?.query || []).forEach(row => addRow($("#query-fields"), "query", row));
            (draft?.headers || []).forEach(row => addRow($("#extra-header-fields"), "header", row));
            const documentedMedia = Object.keys(operation.requestBody?.content || {});
            const mediaTypes = documentedMedia.length ? documentedMedia : [...defaultMediaTypes];
            if (draft?.mediaType && !mediaTypes.includes(draft.mediaType)) mediaTypes.push(draft.mediaType);
            const mediaSelect = $("#body-type").empty();
            mediaTypes.forEach(type => $("<option>").val(type).text(type).appendTo(mediaSelect));
            mediaSelect.val(draft?.mediaType || mediaTypes[0]);
            const permitsBody = api.permitsBody(operation.method);
            $("#body-fields").prop("hidden", !permitsBody);
            $("#body-unavailable").prop("hidden", permitsBody);
            $("#body-label").text(operation.requestBody?.required ? "请求体（必填）" : "请求体");
            $("#body-input").val(draft?.body || "");
            updateBodyHint();
            setRequestError("");
            fillGlobalHeaders();
            refreshUrl();
        }

        function createParameterGroup(container, title, parameters, draft, append = false) {
            if (!append) container.empty();
            if (!parameters.length) return;
            const group = $("<div>", { class: "field-group" }).appendTo(container);
            $("<h3>", { class: "field-label" }).text(title).appendTo(group);
            for (const parameter of parameters) {
                const privateValue = isPrivateParameter(parameter);
                const row = $("<label>", { class: "parameter-row" }).appendTo(group);
                const info = $("<span>").appendTo(row);
                $("<span>", { class: "parameter-name" }).text(parameter.name).appendTo(info);
                const details = $("<span>", { class: "parameter-meta" }).appendTo(info);
                $("<span>").text(parameter.type || parameter.contentType || "string").appendTo(details);
                if (parameter.required) $("<span>", { class: "parameter-required" }).text("必填").appendTo(details);
                if (parameter.description) $("<span>", { class: "parameter-description block" }).text(parameter.description).appendTo(info);
                const example = privateValue ? undefined : api.exampleValue(definition, parameter) ?? parameter.schema?.default;
                const jsonValue = api.isJson(parameter.contentType);
                const inputExample = jsonValue ? JSON.stringify(example) ?? "" : formatValue(example);
                const fallback = jsonValue ? "输入 JSON 值" : parameter.type === "array" ? "逗号分隔或 JSON 数组"
                    : parameter.type === "object" ? "JSON 对象" : "输入值";
                const input = $("<input>", {
                    class: "field parameter-input", type: privateValue ? "password" : "text",
                    autocomplete: "off", "data-parameter-key": parameter.key, "aria-label": parameter.name,
                    "aria-required": parameter.required, placeholder: example === undefined ? fallback : inputExample
                }).val(draft ? (draft.parameters[parameter.key] || "") : inputExample).appendTo(row);
                if (parameter.in === "header" && !draft?.parameters[parameter.key]) {
                    input.data("initialHeader", { value: input.val(), type: input.attr("type") })
                        .one("input", () => input.removeData("initialHeader").removeAttr("data-global-header"));
                }
            }
        }

        function isPrivateParameter(parameter) {
            return parameter.in === "header" ? credentials.headers.has(parameter.name.toLowerCase())
                : parameter.in === "query" && credentials.query.has(parameter.name);
        }

        function addRow(container, kind, values, plainText = false) {
            const label = kind === "query" ? "查询参数" : "请求头";
            const row = $("<div>", { class: "extra-row" }).appendTo(container);
            const name = $("<input>", { class: "field row-name", "aria-label": `${label}名称`, placeholder: "名称", autocomplete: "off" })
                .val(values?.name || "").appendTo(row);
            const value = $("<input>", { class: "field row-value", "aria-label": `${label}值`, placeholder: "值", autocomplete: "off" })
                .val(values?.value || "").appendTo(row);
            if (!plainText) {
                const markPrivate = () => value.attr("type", isPrivateParameter({ in: kind, name: name.val().trim() }) ? "password" : "text");
                name.on("input", markPrivate);
                markPrivate();
            }
            name.add(value).on("input", () => value.removeAttr("data-global-header"));
            $("<button>", { type: "button", class: "row-remove", title: `移除${label}`, "aria-label": `移除${label}` })
                .text("×").on("click", () => { row.remove(); refreshUrl(); }).appendTo(row);
            if (!values) name.trigger("focus");
            return row;
        }

        function updateBodyHint() {
            if (!currentOperation) return;
            const type = $("#body-type").val();
            const media = currentOperation.requestBody?.content?.[type];
            const schema = api.resolveReference(definition, media?.schema);
            const form = type === "multipart/form-data" || type === "application/x-www-form-urlencoded";
            $("#body-input").attr("placeholder", form ? '{ "name": "value" }' : api.isJson(type) ? '{ "key": "value" }' : "输入请求体");
            const help = type === "multipart/form-data" ? "用 JSON 对象编辑表单字段。此处支持文本字段。"
                : form ? "用 JSON 对象编辑表单字段，发送时会转换为 URL 编码。" : currentOperation.requestBody?.description || "";
            $("#body-help").text(help).prop("hidden", !help);
            renderFieldDescriptions("#request-field-descriptions", [{ fields: api.schemaFieldDescriptions(definition, media?.schema) }]);
            $("#body-schema-code").text(schema ? JSON.stringify(schema, null, 2) : "");
            $("#body-schema").prop("hidden", !schema).prop("open", false);
            $("#insert-example").prop("hidden", bodyExample() === undefined);
        }

        function bodyExample() {
            const media = currentOperation.requestBody?.content?.[$("#body-type").val()];
            return api.exampleValue(definition, media) ?? api.schemaExample(definition, media?.schema);
        }

        function insertBodyExample() {
            const value = bodyExample();
            if (value === undefined) return;
            const type = $("#body-type").val();
            $("#body-input").val(api.isJson(type) || typeof value === "object" ? JSON.stringify(value, null, 2) : String(value));
        }

        function formatValue(value) {
            if (value === undefined) return "";
            return typeof value === "object" ? JSON.stringify(value) : String(value);
        }

        function readGlobalHeaders() {
            let config = null;
            try {
                const persisted = localStorage.getItem(globalHeaderKey);
                const saved = JSON.parse(persisted ?? sessionStorage.getItem(globalHeaderKey));
                if (typeof saved?.origin === "string") {
                    const rows = Array.isArray(saved.headers) ? saved.headers : [saved];
                    const headers = rows.filter(row => typeof row?.name === "string" && typeof row.value === "string");
                    if (headers.length) {
                        config = { origin: saved.origin, headers };
                        if (persisted === null) persistGlobalHeaders(config);
                    }
                }
            } catch {
                // Keep decoded settings usable on the page if migration cannot write them.
            }
            return config;
        }

        function persistGlobalHeaders(config) {
            // A cleared configuration takes precedence over legacy settings in other tabs.
            localStorage.setItem(globalHeaderKey, JSON.stringify(config));
            try {
                sessionStorage.removeItem(globalHeaderKey);
            } catch {
                // The local copy is already saved.
            }
        }

        function serverOrigin() {
            return new URL($("#server-select").val() || specUrl.href).origin;
        }

        function headersForOrigin(origin) {
            return globalHeaders?.origin === origin ? globalHeaders.headers : [];
        }

        function editGlobalHeaders() {
            const origin = serverOrigin();
            const fields = $("#global-header-fields").empty();
            const headers = headersForOrigin(origin);
            $("#global-header-origin").text(origin);
            for (const header of headers.length ? headers : [{}]) addRow(fields, "header", header, true);
            $("#clear-global-header").prop("hidden", !headers.length);
            $("#global-header-error").prop("hidden", true);
            globalHeaderDialog.showModal();
        }

        function saveGlobalHeaders(event) {
            event.preventDefault();
            const error = $("#global-header-error");
            const headers = readRows("#global-header-fields")
                .map(({ name, value }) => ({ name: name.trim(), value: value.trim() }))
                .filter(({ name, value }) => name || value);
            const names = new Set();
            for (const { name, value } of headers) {
                if (!name || !value) {
                    error.text("请填写请求头名称和值。").prop("hidden", false);
                    return;
                }
                const key = name.toLowerCase();
                if (names.has(key)) {
                    error.text(`请求头 ${name} 已存在。`).prop("hidden", false);
                    return;
                }
                names.add(key);
            }
            try {
                new Headers(headers.map(({ name, value }) => [name, value]));
            } catch {
                error.text("请求头名称或值的格式无效。").prop("hidden", false);
                return;
            }
            updateGlobalHeaders(headers);
        }

        function updateGlobalHeaders(headers) {
            const origin = $("#global-header-origin").text();
            if (!headers.length && globalHeaders?.origin !== origin) {
                globalHeaderDialog.close();
                return;
            }
            globalHeaders = headers.length ? { origin, headers } : null;
            let message = globalHeaders ? "已保存全局请求头" : "已清除全局请求头";
            try {
                persistGlobalHeaders(globalHeaders);
            } catch {
                message += "（当前页面生效）";
            }
            fillGlobalHeaders();
            globalHeaderDialog.close();
            notify(message);
        }

        function fillGlobalHeaders() {
            const headers = headersForOrigin(serverOrigin());
            $("#configure-global-header").toggleClass("is-configured", headers.length > 0);
            const defaults = new Map(headers.map(header => [header.name.toLowerCase(), header]));
            const extra = $("#extra-header-fields");
            extra.find("[data-global-header]").closest(".extra-row").remove();
            for (const { name } of readRows(extra)) defaults.delete(name.trim().toLowerCase());
            $("#header-fields .parameter-input").each(function () {
                const key = this.dataset.parameterKey.slice("header:".length);
                const header = defaults.get(key);
                defaults.delete(key);
                const input = $(this);
                const initial = input.data("initialHeader");
                if (!initial) return;
                input.val(header?.value ?? initial.value).attr("type", header ? "text" : initial.type)
                    .attr("data-global-header", header?.name ?? null);
            });
            for (const header of defaults.values()) {
                addRow(extra, "header", header, true).find(".row-value").attr("data-global-header", header.name);
            }
        }

        function collectGlobalHeaders() {
            const headers = $("#request-headers [data-global-header]").map(function () {
                return { name: this.dataset.globalHeader, value: this.value };
            }).get();
            return { origin: globalHeaders?.origin, headers };
        }

        function collectDraft() {
            const parameters = {};
            $(".parameter-input").not("[data-global-header]").each(function () { parameters[this.dataset.parameterKey] = this.value; });
            return {
                server: $("#server-select").val(),
                parameters,
                query: readRows("#query-fields"),
                headers: readRows("#extra-header-fields"),
                mediaType: $("#body-type").val(),
                body: $("#body-input").val()
            };
        }

        function readRows(container) {
            return $(container).children(".extra-row").map(function () {
                const value = $(this).find(".row-value");
                if (value.is("[data-global-header]")) return null;
                return { name: $(this).find(".row-name").val(), value: value.val() };
            }).get();
        }

        function refreshUrl() {
            if (!currentOperation) return;
            try {
                const url = api.projectUrl(currentOperation, collectDraft()).href;
                $("#request-url").text(url.replace(/%7B/gi, "{").replace(/%7D/gi, "}"));
            } catch {
                $("#request-url").text("完善参数后可预览请求 URL。");
            }
        }

        async function sendRequest(event) {
            event.preventDefault();
            if (!currentOperation || currentOperation.archived || controller) return;
            setRequestError("");
            const operation = currentOperation;
            const draft = collectDraft();
            let request;
            try {
                request = api.buildRequest(operation, draft, collectGlobalHeaders());
            } catch (error) {
                setRequestError(error.message || "请检查请求参数。");
                return;
            }
            const revision = ++selection;
            const snapshot = api.historySnapshot(operation, draft, request.url, credentials);
            const record = {
                scope, operationKey: operation.key, method: operation.method, path: operation.path, title: operation.title,
                startedAt: Date.now(), url: snapshot.url, draft: snapshot.draft
            };
            const started = performance.now();
            controller = new AbortController();
            updateSendButton();
            clearResponse("请求发送中…");
            switchPanel("response", "content");
            try {
                const response = await fetch(request.url, { ...request.options, signal: controller.signal });
                record.status = response.status;
                record.statusText = response.statusText;
                record.responseHeaders = [...response.headers];
                record.mediaType = response.headers.get("content-type") || "";
                record.responseBody = await response.blob();
            } catch (error) {
                record.error = error.name === "AbortError" ? "请求已取消" : (record.status ? "读取响应失败" : "请求失败");
                record.errorDetail = error.name === "AbortError" ? "已停止等待响应。" : error.message;
            } finally {
                record.elapsed = Math.round(performance.now() - started);
                controller = null;
                updateSendButton();
            }
            if (revision === selection) renderResponse(record);
            try {
                record.id = await history.add(record);
                records = [record, ...records.filter(item => item.id !== record.id)];
                renderHistory();
            } catch {
                notify("浏览器无法保存本次请求记录。");
            }
        }

        function updateSendButton() {
            const busy = Boolean(controller);
            $("#send-request").prop("disabled", busy || Boolean(currentOperation?.archived)).text(busy ? "发送中…" : "发送请求");
            $("#cancel-request").prop("hidden", !busy);
        }

        function setRequestError(message) {
            $("#request-error").text(message).prop("hidden", !message);
        }

        function renderFieldDescriptions(target, groups) {
            const details = $(target);
            const list = details.children(".schema-field-groups").empty();
            for (const { label, fields } of groups) {
                if (!fields.length) continue;
                const group = $("<section>", { class: "schema-field-group" }).appendTo(list);
                if (label) $("<h3>", { class: "schema-field-label" }).text(label).appendTo(group);
                const rows = $("<dl>").appendTo(group);
                for (const { path, description } of fields) {
                    $("<div>", { class: "schema-field-row" }).append(
                        $("<dt>").text(path), $("<dd>").text(description)
                    ).appendTo(rows);
                }
            }
            details.prop("hidden", !list.children().length).prop("open", false);
        }

        function renderResponseFieldDescriptions(record) {
            const schemas = api.responseBodySchemas(definition, currentOperation?.responses, record?.status, record?.mediaType);
            const groups = schemas.map(({ code, type, schema }) => ({
                label: `${code} · ${type}`,
                fields: api.schemaFieldDescriptions(definition, schema)
            }));
            renderFieldDescriptions("#response-field-descriptions", groups);
        }

        function renderResponseDocs(operation) {
            const list = $("#response-doc-list").empty();
            const responses = Object.entries(operation.responses);
            if (!responses.length) $("<p>", { class: "field-help" }).text("文档未声明响应。").appendTo(list);
            for (const [code, schema] of responses) {
                const response = api.resolveReference(definition, schema);
                const detail = $("<details>", { class: "response-doc" }).appendTo(list);
                $("<summary>").text(`${code}  ${response?.description || "响应"}`).appendTo(detail);
                for (const [type, media] of Object.entries(response?.content || {})) {
                    $("<code>", { class: "response-doc-media" }).text(type).appendTo(detail);
                    if (media.schema) {
                        const model = api.resolveReference(definition, media.schema);
                        $("<pre>", { class: "schema-code" }).text(JSON.stringify(model, null, 2)).appendTo(detail);
                    }
                }
                if (response?.headers) $("<p>", { class: "field-help mt-2" }).text(`响应头：${Object.keys(response.headers).join("、")}`).appendTo(detail);
                if (response?.$ref) $("<code>", { class: "response-doc-media" }).text(response.$ref).appendTo(detail);
            }
        }

        function clearResponse(label = "等待发送请求") {
            shownResponse = undefined;
            $("#response-meta").text(label);
            $("#response-status, #result-actions, #response-code, #response-note, #response-field-descriptions").prop("hidden", true);
            $("#response-empty").prop("hidden", false);
            $("#response-header-list").empty();
            $("#response-headers-empty").prop("hidden", false);
        }

        async function renderResponse(record) {
            clearResponse();
            if (!record?.error || record.status) renderResponseFieldDescriptions(record);
            if (!record) return;
            shownResponse = record;
            const blob = record.responseBody;
            const statusLabel = record.status ? `${record.status}${record.statusText ? ` ${record.statusText}` : ""}` : record.error;
            const size = blob ? ` · ${formatSize(blob.size)}` : "";
            $("#response-meta").text(`${record.elapsed} ms${size}`);
            $("#response-status").text(statusLabel).attr("data-tone", record.error || record.status >= 400 ? "error"
                : record.status < 300 ? "success" : "neutral").prop("hidden", false);
            $("#response-empty").prop("hidden", true);
            const headers = record.responseHeaders || [];
            for (const [name, value] of headers) {
                $("<div>", { class: "response-header-row" }).append($("<dt>").text(name), $("<dd>").text(value)).appendTo("#response-header-list");
            }
            $("#response-headers-empty").prop("hidden", headers.length > 0);
            if (record.error) {
                $("#response-code").text(record.errorDetail || record.error).prop("hidden", false);
                return;
            }
            $("#result-actions").prop("hidden", false);
            $("#copy-response").prop("hidden", true);
            $("#download-response").prop("hidden", !blob?.size);
            if (!blob?.size) {
                $("#response-code").text("（无响应体）").prop("hidden", false);
                return;
            }
            if (!textual(record.mediaType)) {
                $("#response-code").text("二进制响应。可下载查看完整内容。").prop("hidden", false);
                return;
            }
            const truncated = blob.size > responsePreviewSize;
            const preview = await blob.slice(0, responsePreviewSize).text();
            if (record !== shownResponse) return;
            $("#response-code").text(truncated ? preview : prettyResponse(preview)).prop("hidden", false);
            $("#copy-response").prop("hidden", truncated);
            $("#response-note").text("当前预览前 256 KB。下载文件保留完整响应。").prop("hidden", !truncated);
        }

        function textual(type) {
            const media = api.baseMediaType(type);
            return !media || media.startsWith("text/") || api.isJson(media) || /(?:[/+]xml|\/javascript|\/x-www-form-urlencoded)$/.test(media);
        }

        function prettyResponse(text) {
            try { JSON.parse(text); }
            catch { return text; }

            // Format JSON tokens without rounding long integer values through JavaScript Number.
            const tokens = text.match(/"(?:\\.|[^"\\])*"|[{}\[\],:]|[^{}\[\],:\s]+/g);
            let depth = 0;
            const line = () => `\n${"  ".repeat(Math.min(depth, 32))}`;
            return tokens.map((token, index) => {
                if (token === "{" || token === "[") {
                    depth++;
                    return token + (["}", "]"].includes(tokens[index + 1]) ? "" : line());
                }
                if (token === "}" || token === "]") {
                    depth--;
                    return (["{", "["].includes(tokens[index - 1]) ? "" : line()) + token;
                }
                if (token === ",") return token + line();
                return token === ":" ? ": " : token;
            }).join("");
        }

        function formatSize(size) {
            return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`;
        }

        async function copyResponse() {
            const text = $("#response-code").text();
            try {
                if (!navigator.clipboard) throw new Error();
                await navigator.clipboard.writeText(text);
                notify("已复制响应体");
            } catch {
                const range = document.createRange();
                range.selectNodeContents($("#response-code")[0]);
                window.getSelection().removeAllRanges();
                window.getSelection().addRange(range);
                notify("已选中响应体，可按快捷键复制");
            }
        }

        function downloadResponse() {
            const blob = shownResponse?.responseBody;
            if (!blob) return;
            const type = api.baseMediaType(shownResponse.mediaType);
            const ending = api.isJson(type) ? "json" : type.endsWith("xml") ? "xml" : textual(type) ? "txt" : "bin";
            const url = URL.createObjectURL(blob);
            const link = $("<a>", { href: url, download: `response-${shownResponse.startedAt}.${ending}`, hidden: true }).appendTo("body");
            link[0].click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        }

        function switchNavigation(section) {
            $("[data-navigation]").each(function () {
                const active = this.dataset.navigation === section;
                $(this).toggleClass("is-active", active).attr("aria-pressed", active);
            });
            $("#nav-operations").prop("hidden", section !== "operations");
            $("#nav-history").prop("hidden", section !== "history");
            if (section === "history") loadHistory();
        }

        function switchPanel(group, section) {
            $(`[data-${group}-tab]`).each(function () {
                const tab = this.dataset[`${group}Tab`];
                const active = tab === section;
                $(this).toggleClass("is-active", active).attr("aria-pressed", active);
                $(`#${group}-${tab}`).prop("hidden", !active);
            });
        }

        async function loadHistory() {
            try {
                records = await history.list(scope);
                renderHistory();
            } catch {
                $("#history-empty").text("当前无法读取浏览器中的记录。").prop("hidden", false);
            }
        }

        function renderHistory() {
            const list = $("#history-list").empty();
            for (const record of records) {
                const label = record.status ? String(record.status) : record.error || "";
                const active = record.id === selectedRecord?.id;
                $("<button>", { type: "button", class: "history-item", title: record.url })
                    .toggleClass("is-active", active).attr("aria-current", active ? "true" : "false")
                    .append(entryLine(record.method, record.path))
                    .append($("<p>", { class: "history-date" }).append(
                        $("<span>").text(dateFormat.format(record.startedAt)),
                        $("<span>", { class: record.error || record.status >= 400 ? "text-red-700" : "text-emerald-800" }).text(label)
                    )).on("click", () => restoreRecord(record)).appendTo(list);
            }
            $("#clear-history").prop("disabled", !records.length);
            $("#history-empty").text("暂无请求记录").prop("hidden", records.length > 0);
            const selected = selectedRecord && records.some(record => record.id === selectedRecord.id);
            $("#history-detail").prop("hidden", !selected);
            if (selected) $("#history-detail-label").text(dateFormat.format(selectedRecord.startedAt));
        }

        function restoreRecord(record) {
            const operation = operations.find(item => item.key === record.operationKey) || {
                key: record.operationKey, archived: true, method: record.method, path: record.path, title: record.title,
                servers: [{ url: record.draft.server }], responses: {},
                parameters: Object.keys(record.draft.parameters).map(key => {
                    const [location, name] = key.split(/:(.*)/s);
                    return { key, name, in: location, type: "string" };
                })
            };
            selectOperation(operation, record);
        }

        async function clearHistory() {
            if (!window.confirm("清空当前应用的请求历史？")) return;
            try {
                await history.clear(scope);
                selectedRecord = undefined;
                await loadHistory();
                notify("已清空请求历史");
            } catch { notify("无法清空请求历史。"); }
        }

        async function deleteSelectedRecord() {
            if (!selectedRecord) return;
            try {
                await history.remove(selectedRecord.id);
                selectedRecord = undefined;
                await loadHistory();
                notify("已删除这条记录");
            } catch { notify("无法删除这条记录。"); }
        }

        function notify(message) {
            clearTimeout(toastTimer);
            $("#toast").text(message).prop("hidden", false);
            toastTimer = setTimeout(() => $("#toast").prop("hidden", true), 4000);
        }
    });
}(window.jQuery, window.WebMindOpenApi, window.WebMindHistory));
