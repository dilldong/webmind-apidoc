(function () {
    "use strict";

    const methods = new Set(["get", "put", "post", "delete", "options", "head", "patch", "trace"]);
    const parameterLocations = new Set(["path", "query", "header", "cookie"]);
    const credentialHeaders = new Set(["authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key"]);

    function resolveReference(document, value) {
        const seen = new Set();
        return resolve(value);

        function resolve(node) {
            if (typeof node?.$ref !== "string" || !node.$ref.startsWith("#/") || seen.has(node.$ref)) {
                return node;
            }
            seen.add(node.$ref);
            let target = document;
            for (const token of decodeURIComponent(node.$ref.substring(2)).split("/")) {
                const key = token.replaceAll("~1", "/").replaceAll("~0", "~");
                if (!target || !Object.hasOwn(target, key)) return node;
                target = target[key];
            }
            const { $ref, ...siblings } = node;
            return { ...resolve(target), ...siblings };
        }
    }

    function operationList(document, specUrl) {
        if (!document || typeof document.openapi !== "string" || !document.paths || typeof document.paths !== "object") {
            throw new Error("文档缺少 OpenAPI 版本或接口路径。");
        }
        const operations = [];
        for (const [path, pathDefinition] of Object.entries(document.paths)) {
            if (!path.startsWith("/")) continue;
            const pathItem = resolveReference(document, pathDefinition);
            for (const [name, operation] of Object.entries(pathItem || {})) {
                if (!methods.has(name) || !operation || typeof operation !== "object") continue;
                const method = name.toUpperCase();
                const tags = Array.isArray(operation.tags) ? operation.tags.filter(tag => typeof tag === "string" && tag.trim()) : [];
                operations.push({
                    key: `${method} ${path}`,
                    path,
                    method,
                    title: operation.summary || operation.operationId || path,
                    description: operation.description,
                    tags: [...new Set(tags)],
                    parameters: parametersFor(document, pathItem.parameters, operation.parameters),
                    requestBody: resolveReference(document, operation.requestBody),
                    responses: operation.responses || {},
                    servers: serversFor(specUrl, operation.servers, pathItem.servers, document.servers)
                });
            }
        }
        return operations;
    }

    function groupOperations(document, operations) {
        const groups = new Map();
        const tags = Array.isArray(document.tags) ? document.tags : [];
        for (const tag of tags) {
            if (typeof tag?.name === "string") groups.set(tag.name, []);
        }
        const untagged = [];
        for (const operation of operations) {
            if (!operation.tags.length) untagged.push(operation);
            for (const tag of operation.tags) {
                if (!groups.has(tag)) groups.set(tag, []);
                groups.get(tag).push(operation);
            }
        }
        groups.set(null, untagged);
        return [...groups].filter(([, entries]) => entries.length);
    }

    function parametersFor(document, inherited = [], overrides = []) {
        const parameters = new Map();
        for (const definition of [...inherited, ...overrides]) {
            const parameter = resolveReference(document, definition);
            if (typeof parameter?.name !== "string" || !parameterLocations.has(parameter.in)) continue;
            const contentType = Object.keys(parameter.content || {})[0];
            const media = parameter.content?.[contentType];
            const schema = resolveReference(document, parameter.schema || media?.schema);
            const key = `${parameter.in}:${parameter.in === "header" ? parameter.name.toLowerCase() : parameter.name}`;
            parameters.set(key, {
                ...parameter,
                key,
                schema,
                contentType,
                required: parameter.in === "path" || parameter.required === true,
                type: Array.isArray(schema?.type) ? schema.type.find(type => type !== "null") : schema?.type
            });
        }
        return [...parameters.values()];
    }

    function serversFor(specUrl, ...serverGroups) {
        const definitions = serverGroups.find(group => Array.isArray(group) && group.length) || [];
        const servers = new Map();
        for (const definition of definitions) {
            if (typeof definition?.url !== "string") continue;
            const value = definition.url.replace(/\{([^{}]+)\}/g,
                (token, name) => definition.variables?.[name]?.default ?? token);
            try {
                const url = httpUrl(value, specUrl);
                if (/[{}]/.test(value)) continue;
                servers.set(url.href, { url: url.href, label: definition.description || "" });
            } catch {
                // Keep usable HTTP servers when an OpenAPI document also lists another protocol.
            }
        }
        const contextRoot = new URL("../", specUrl).href;
        return servers.size ? [...servers.values()] : [{ url: contextRoot, label: "当前应用" }];
    }

    function httpUrl(value, base) {
        const url = new URL(value, base);
        if (!/^https?:$/.test(url.protocol) || url.username || url.password) {
            throw new Error("服务地址需要使用 HTTP 或 HTTPS。");
        }
        url.hash = "";
        return url;
    }

    function exampleValue(document, from) {
        if (!from) return undefined;
        if (Object.hasOwn(from, "example")) return from.example;
        for (const definition of Object.values(from.examples || {})) {
            const example = resolveReference(document, definition);
            if (example && Object.hasOwn(example, "value")) return example.value;
        }
        return undefined;
    }

    function schemaExample(document, schema, references = new Set()) {
        if (!schema || (schema.$ref && references.has(schema.$ref))) return undefined;
        const nextReferences = schema.$ref ? new Set([...references, schema.$ref]) : references;
        const model = resolveReference(document, schema);
        if (Object.hasOwn(model, "example")) return model.example;
        if (model.examples?.length) return model.examples[0];
        if (Object.hasOwn(model, "default")) return model.default;
        if (Object.hasOwn(model, "const")) return model.const;
        if (model.enum?.length) return model.enum[0];
        if (model.items) {
            const item = schemaExample(document, model.items, nextReferences);
            return item === undefined ? [] : [item];
        }
        if (model.properties) {
            const properties = Object.entries(model.properties)
                .filter(([, value]) => !resolveReference(document, value)?.readOnly)
                .map(([name, value]) => [name, schemaExample(document, value, nextReferences)]);
            return Object.fromEntries(properties.filter(([, value]) => value !== undefined));
        }
        const type = Array.isArray(model.type) ? model.type.find(item => item !== "null") : model.type;
        return { string: "string", integer: 0, number: 0, boolean: false, array: [], object: {}, null: null }[type];
    }

    function schemaFieldDescriptions(document, schema) {
        const fields = new Map();
        const references = new Set();
        visit(schema, "");
        return [...fields].map(([path, descriptions]) => ({ path, description: [...descriptions].join("\n") }));

        function visit(value, path) {
            if (!value || typeof value !== "object") return;
            const model = resolveReference(document, value);
            const description = typeof model.description === "string" ? model.description.trim() : "";
            if (path && description) {
                if (!fields.has(path)) fields.set(path, new Set());
                fields.get(path).add(description);
            }

            const ref = value.$ref;
            if (references.has(ref)) return;
            if (ref) references.add(ref);
            for (const [name, property] of Object.entries(model.properties || {})) {
                const field = name && !/[.\[\]*]/.test(name) ? (path ? `${path}.${name}` : name) : `${path}[${JSON.stringify(name)}]`;
                visit(property, field);
            }
            visit(model.items, `${path}[]`);
            visit(model.additionalProperties, path ? `${path}.*` : "*");
            if (Array.isArray(model.prefixItems)) model.prefixItems.forEach((item, index) => visit(item, `${path}[${index}]`));
            for (const keyword of ["allOf", "oneOf", "anyOf"]) {
                if (Array.isArray(model[keyword])) model[keyword].forEach(variant => visit(variant, path));
            }
            if (ref) references.delete(ref);
        }
    }

    function responseBodySchemas(document, responses = {}, status, mediaType) {
        let entries = Object.entries(responses);
        if (status) {
            const code = String(status);
            const matches = [code, `${code[0]}XX`, "default"];
            const key = matches.find(candidate => Object.hasOwn(responses, candidate));
            entries = key ? [[key, responses[key]]] : [];
        }
        return entries.flatMap(([code, entry]) => {
            const response = resolveReference(document, entry);
            const content = Object.entries(response?.content || {});
            const type = baseMediaType(mediaType);
            const category = type.split("/", 1)[0];
            const match = type && [type, `${category}/*`, "*/*"].find(candidate => content.some(([name]) => baseMediaType(name) === candidate));
            const selected = match ? content.filter(([name]) => baseMediaType(name) === match) : content;
            return selected.map(([type, media]) => ({ code, type, schema: media?.schema }));
        });
    }

    function parameterValue(parameter, raw) {
        try {
            if (isJson(parameter.contentType)) return JSON.stringify(JSON.parse(raw));
            if (parameter.type === "array") {
                const values = raw.trimStart().startsWith("[") ? JSON.parse(raw) : raw.split(",").map(value => value.trim());
                if (!Array.isArray(values)) throw new Error();
                return values;
            }
            if (parameter.type === "object") return parseObject(raw);
            return raw;
        } catch {
            throw new Error(`参数 ${parameter.name} 的值与文档类型不符。`);
        }
    }

    function parseObject(text) {
        const value = JSON.parse(text);
        if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("需要 JSON 对象。");
        return value;
    }

    function simpleValue(value, explode, encode = String) {
        if (Array.isArray(value)) return value.map(encode).join(",");
        if (typeof value !== "object" || value === null) return encode(value);
        return Object.entries(value).map(([key, item]) => `${encode(key)}${explode ? "=" : ","}${encode(item)}`).join(",");
    }

    function pathValue(parameter, value) {
        const encodedName = encodeURIComponent(parameter.name);
        const encode = encodeURIComponent;
        if (parameter.style === "matrix") {
            if (parameter.explode && Array.isArray(value)) return value.map(item => `;${encodedName}=${encode(item)}`).join("");
            if (parameter.explode && typeof value === "object") {
                return Object.entries(value).map(([key, item]) => `;${encode(key)}=${encode(item)}`).join("");
            }
            return `;${encodedName}=${simpleValue(value, false, encode)}`;
        }
        if (parameter.style === "label") {
            if (parameter.explode && Array.isArray(value)) return `.${value.map(encode).join(".")}`;
            if (parameter.explode && typeof value === "object") {
                return `.${Object.entries(value).map(([key, item]) => `${encode(key)}=${encode(item)}`).join(".")}`;
            }
            return `.${simpleValue(value, false, encode)}`;
        }
        return simpleValue(value, parameter.explode, encode);
    }

    function appendQuery(query, parameter, value) {
        const { name, style = "form", explode = style === "form" } = parameter;
        if (Array.isArray(value)) {
            if (explode) value.forEach(item => query.append(name, item));
            else query.append(name, value.join({ pipeDelimited: "|", spaceDelimited: " " }[style] || ","));
        } else if (value !== null && typeof value === "object") {
            if (style === "deepObject") {
                Object.entries(value).forEach(([key, item]) => query.append(`${name}[${key}]`, item));
            } else if (explode) {
                Object.entries(value).forEach(([key, item]) => query.append(key, item));
            } else {
                query.append(name, simpleValue(value, false));
            }
        } else {
            query.append(name, value);
        }
    }

    function projectUrl(operation, draft, strict = false) {
        const url = httpUrl(draft.server);
        let path = operation.path;
        for (const parameter of operation.parameters.filter(item => item.in === "path")) {
            const raw = draft.parameters[parameter.key];
            if (!raw?.trim()) {
                if (strict) throw new Error(`请填写路径参数 ${parameter.name}。`);
                continue;
            }
            path = path.replaceAll(`{${parameter.name}}`, () => pathValue(parameter, parameterValue(parameter, raw)));
        }
        if (strict && /\{[^{}]+\}/.test(path)) throw new Error("请求路径还有未填写的变量。");
        if (strict && path.split("/").some(segment => segment === "." || segment === "..")) {
            throw new Error("浏览器会改写包含 . 或 .. 片段的路径，请检查路径参数。");
        }
        url.pathname = url.pathname.replace(/\/$/, "") + path;
        for (const parameter of operation.parameters.filter(item => item.in === "query")) {
            const raw = draft.parameters[parameter.key];
            if (!raw?.trim()) {
                if (strict && parameter.required) throw new Error(`请填写查询参数 ${parameter.name}。`);
                continue;
            }
            appendQuery(url.searchParams, parameter, parameterValue(parameter, raw));
        }
        for (const { name, value } of draft.query) {
            if (name.trim()) url.searchParams.append(name.trim(), value);
        }
        return url;
    }

    function buildRequest(operation, draft, globalHeaders) {
        if (operation.method === "TRACE") throw new Error("浏览器 Fetch 不支持发送 TRACE 请求。");
        const url = projectUrl(operation, draft, true);
        const headers = new Headers();
        const headerParameters = operation.parameters.filter(item => item.in === "header");
        for (const parameter of headerParameters) {
            const raw = draft.parameters[parameter.key];
            if (raw?.trim()) headers.set(parameter.name, simpleValue(parameterValue(parameter, raw), parameter.explode));
        }
        for (const { name, value } of draft.headers) {
            if (name.trim()) headers.set(name.trim(), value);
        }
        const inherited = globalHeaders?.origin === url.origin
            ? globalHeaders.headers.filter(({ name }) => !headers.has(name)) : [];
        for (const { name, value } of inherited) headers.set(name, value);
        for (const { required, name } of headerParameters) {
            if (required && !headers.get(name)) throw new Error(`请填写请求头 ${name}。`);
        }
        const body = buildBody(operation, draft, headers);
        const options = { method: operation.method, headers, body, credentials: "same-origin" };
        // Fetch can forward custom headers to another origin when following a redirect.
        if (inherited.some(({ name }) => headers.has(name))) options.redirect = "error";
        return { url, options };
    }

    function buildBody(operation, draft, headers) {
        if (!permitsBody(operation.method)) return undefined;
        if (!draft.body.trim()) {
            if (operation.requestBody?.required) throw new Error("请填写请求体。");
            return undefined;
        }
        const media = baseMediaType(draft.mediaType);
        if (media === "multipart/form-data" || media === "application/x-www-form-urlencoded") {
            let fields;
            try {
                fields = parseObject(draft.body);
            } catch {
                throw new Error("表单请求体需要填写 JSON 对象。");
            }
            const body = media === "multipart/form-data" ? new FormData() : new URLSearchParams();
            for (const [key, value] of Object.entries(fields)) {
                for (const item of Array.isArray(value) ? value : [value]) {
                    body.append(key, item !== null && typeof item === "object" ? JSON.stringify(item) : String(item ?? ""));
                }
            }
            // Fetch supplies the multipart boundary or the form content type.
            headers.delete("content-type");
            return body;
        }
        if (isJson(media)) {
            try {
                JSON.parse(draft.body);
            } catch {
                throw new Error("请求体不是有效的 JSON。");
            }
        }
        if (!headers.has("content-type")) headers.set("content-type", draft.mediaType || "text/plain;charset=UTF-8");
        return draft.body;
    }

    function baseMediaType(value) {
        return (value || "").split(";", 1)[0].trim().toLowerCase();
    }

    function isJson(value) {
        const type = baseMediaType(value);
        return type === "application/json" || type.endsWith("+json");
    }

    function permitsBody(method) {
        return method !== "GET" && method !== "HEAD" && method !== "TRACE";
    }

    function credentialsFor(document) {
        const headers = new Set(credentialHeaders);
        const query = new Set();
        for (const definition of Object.values(document.components?.securitySchemes || {})) {
            const scheme = resolveReference(document, definition);
            if (scheme?.type !== "apiKey" || !scheme.name) continue;
            if (scheme.in === "header") headers.add(scheme.name.toLowerCase());
            if (scheme.in === "query") query.add(scheme.name);
        }
        return { headers, query };
    }

    function historySnapshot(operation, draft, url, credentials) {
        const isPrivate = (location, name) => location === "header" ? credentials.headers.has(name.toLowerCase())
            : location === "query" && credentials.query.has(name);
        const safeUrl = new URL(url);
        for (const name of credentials.query) {
            if (safeUrl.searchParams.has(name)) safeUrl.searchParams.set(name, "已隐藏");
        }
        const parameters = Object.fromEntries(operation.parameters
            .filter(parameter => !isPrivate(parameter.in, parameter.name))
            .map(parameter => [parameter.key, draft.parameters[parameter.key]]));
        return {
            url: safeUrl.href,
            draft: {
                ...draft,
                parameters,
                headers: draft.headers.filter(row => !isPrivate("header", row.name.trim())),
                query: draft.query.filter(row => !isPrivate("query", row.name.trim()))
            }
        };
    }

    window.WebMindOpenApi = {
        resolveReference, operationList, groupOperations, exampleValue, schemaExample, schemaFieldDescriptions, responseBodySchemas,
        projectUrl, buildRequest,
        baseMediaType, isJson, permitsBody, credentialsFor, historySnapshot
    };
}());
