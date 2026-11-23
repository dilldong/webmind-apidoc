(function () {
    "use strict";

    const databaseName = "webmind-apidoc";
    const storeName = "requests";
    let connection;

    function openDatabase() {
        if (connection) return connection;
        connection = new Promise((resolve, reject) => {
            if (!window.indexedDB) {
                reject(new Error("当前浏览器无法使用 IndexedDB。"));
                return;
            }
            const request = indexedDB.open(databaseName, 1);
            request.onupgradeneeded = () => {
                const store = request.result.createObjectStore(storeName, { keyPath: "id", autoIncrement: true });
                store.createIndex("scope", "scope");
            };
            request.onsuccess = () => {
                const database = request.result;
                database.onversionchange = () => {
                    database.close();
                    connection = null;
                };
                resolve(database);
            };
            request.onerror = () => reject(request.error);
        });
        connection.catch(() => { connection = null; });
        return connection;
    }

    async function access(mode, action) {
        const database = await openDatabase();
        return new Promise((resolve, reject) => {
            const transaction = database.transaction(storeName, mode);
            const result = action(transaction.objectStore(storeName));
            transaction.oncomplete = () => resolve(result?.result);
            transaction.onerror = () => reject(transaction.error);
            transaction.onabort = () => reject(transaction.error);
        });
    }

    function clear(scope) {
        return access("readwrite", store => {
            const request = store.index("scope").openCursor(IDBKeyRange.only(scope));
            request.onsuccess = () => {
                const cursor = request.result;
                if (!cursor) return;
                cursor.delete();
                cursor.continue();
            };
        });
    }

    window.WebMindHistory = {
        async list(scope) {
            const records = await access("readonly", store => store.index("scope").getAll(scope));
            return records.reverse();
        },
        add: record => access("readwrite", store => store.add(record)),
        remove: id => access("readwrite", store => store.delete(id)),
        clear
    };
}());
