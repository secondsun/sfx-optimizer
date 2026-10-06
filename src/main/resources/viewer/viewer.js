(function () {
    const LINE_HEIGHT = 24;
    const COL_WIDTH = 80;

    let analysisData = null;
    let activeKey = null;
    let activeScope = null;
    let selectedScope = 'all'; // 'all' or function name
    let isPinned = false;
    let isTooltipDismissed = false;
    let viewMode = 'pre'; // 'pre' or 'post'

    // DOM Elements
    const fileSelect = document.getElementById('fileSelect');
    const scopeSelect = document.getElementById('scopeSelect');
    const refreshBtn = document.getElementById('refreshBtn');
    const modePreBtn = document.getElementById('modePreBtn');
    const modePostBtn = document.getElementById('modePostBtn');
    const codePane = document.getElementById('codePane');
    const codePaneTitle = document.getElementById('codePaneTitle');
    const codeScopeBadge = document.getElementById('codeScopeBadge');
    const codeContent = document.getElementById('codeContent');
    const swimlanePane = document.getElementById('swimlanePane');
    const swimlaneHeaders = document.getElementById('swimlaneHeaders');
    const swimlaneBody = document.getElementById('swimlaneBody');
    const splitWrapper = document.getElementById('splitWrapper');
    const svgOverlay = document.getElementById('svgOverlay');
    const svgPathsGroup = document.getElementById('svgPathsGroup');
    const infoTooltip = document.getElementById('infoTooltip');
    const ttCloseBtn = document.getElementById('ttCloseBtn');
    const pinStatus = document.getElementById('pinStatus');
    const pinText = document.getElementById('pinText');
    const unpinBtn = document.getElementById('unpinBtn');
    const intervalCount = document.getElementById('intervalCount');
    const regCount = document.getElementById('regCount');

    // Tooltip elements
    const ttVarName = document.getElementById('ttVarName');
    const ttBadge = document.getElementById('ttBadge');
    const ttScopeRow = document.getElementById('ttScopeRow');
    const ttScope = document.getElementById('ttScope');
    const ttSpan = document.getElementById('ttSpan');
    const ttAllocRow = document.getElementById('ttAllocRow');
    const ttAlloc = document.getElementById('ttAlloc');
    const ttWrites = document.getElementById('ttWrites');
    const ttReads = document.getElementById('ttReads');
    const ttInterferes = document.getElementById('ttInterferes');

    function init() {
        setupEventListeners();
        loadFilesList();

        const urlParams = new URLSearchParams(window.location.search);
        const fileParam = urlParams.get('file');
        if (fileParam) {
            loadFile(fileParam);
        }
    }

    function setupEventListeners() {
        modePreBtn.addEventListener('click', () => setMode('pre'));
        modePostBtn.addEventListener('click', () => setMode('post'));

        refreshBtn.addEventListener('click', () => {
            if (fileSelect.value) {
                loadFile(fileSelect.value);
            }
        });

        fileSelect.addEventListener('change', () => {
            if (fileSelect.value) {
                loadFile(fileSelect.value);
            }
        });

        scopeSelect.addEventListener('change', () => {
            selectedScope = scopeSelect.value;
            onScopeChange();
        });

        unpinBtn.addEventListener('click', unpin);

        if (ttCloseBtn) {
            ttCloseBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                isTooltipDismissed = true;
                hideTooltip();
            });
        }

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                unpin();
            }
        });

        // Synchronize scrolling between code pane and swimlane
        let isSyncingCode = false;
        let isSyncingSwim = false;

        codePane.addEventListener('scroll', () => {
            if (!isSyncingCode) {
                isSyncingSwim = true;
                swimlanePane.scrollTop = codePane.scrollTop;
                isSyncingSwim = false;
            }
            if (activeKey) {
                scheduleRenderConnections(activeKey, activeScope);
                updatePinnedTooltipPosition();
            }
        });

        swimlanePane.addEventListener('scroll', () => {
            if (!isSyncingSwim) {
                isSyncingCode = true;
                codePane.scrollTop = swimlanePane.scrollTop;
                isSyncingCode = false;
            }
            if (activeKey) {
                scheduleRenderConnections(activeKey, activeScope);
                updatePinnedTooltipPosition();
            }
        });

        window.addEventListener('resize', () => {
            if (activeKey) {
                scheduleRenderConnections(activeKey, activeScope);
                updatePinnedTooltipPosition();
            }
        });
    }

    function setMode(mode) {
        viewMode = mode;
        if (mode === 'pre') {
            modePreBtn.classList.add('active');
            modePostBtn.classList.remove('active');
            ttAllocRow.style.display = 'none';
        } else {
            modePostBtn.classList.add('active');
            modePreBtn.classList.remove('active');
            ttAllocRow.style.display = 'flex';
        }
        if (analysisData) {
            renderSwimlaneHeaders();
            if (activeKey) {
                showTooltipFor(activeKey, activeScope);
            }
        }
    }

    function onScopeChange() {
        unpin();
        codeScopeBadge.textContent = selectedScope === 'all' ? 'All Scopes' : selectedScope;

        // If a specific function scope was selected, scroll to its starting line
        if (selectedScope !== 'all' && analysisData && analysisData.functions) {
            const fn = analysisData.functions.find(f => f.name === selectedScope);
            if (fn) {
                const targetEl = codeContent.querySelector(`.code-line[data-line="${fn.startLine}"]`);
                if (targetEl) {
                    targetEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
            }
        }

        renderSwimlaneHeaders();
        renderSwimlaneTracks();
    }

    function unpin() {
        isPinned = false;
        activeKey = null;
        activeScope = null;
        isTooltipDismissed = false;
        pinStatus.classList.add('hidden');
        clearHighlights();
        hideTooltip();
    }

    function pin(key, scope) {
        isPinned = true;
        isTooltipDismissed = false;
        activeKey = key;
        activeScope = scope;
        pinText.textContent = `Pinned: ${key} (${scope || 'all'})`;
        pinStatus.classList.remove('hidden');
        highlightKey(key, scope);
        scheduleRenderConnections(key, scope);
    }

    async function loadFilesList() {
        try {
            const resp = await fetch('/api/files');
            if (!resp.ok) return;
            const files = await resp.json();
            fileSelect.innerHTML = '';
            if (files.length === 0) {
                fileSelect.innerHTML = '<option value="">No assembly files found</option>';
                return;
            }

            const urlParams = new URLSearchParams(window.location.search);
            const currentFile = urlParams.get('file');

            files.forEach(f => {
                const opt = document.createElement('option');
                opt.value = f;
                opt.textContent = f;
                if (currentFile && f === currentFile) {
                    opt.selected = true;
                }
                fileSelect.appendChild(opt);
            });

            if (!currentFile && files.length > 0) {
                loadFile(files[0]);
            }
        } catch (err) {
            console.error('Error fetching file list:', err);
        }
    }

    async function loadFile(filePath) {
        codeContent.innerHTML = '<div class="placeholder-msg">Analyzing ' + escapeHtml(filePath) + '...</div>';
        try {
            const resp = await fetch('/api/file?path=' + encodeURIComponent(filePath));
            if (!resp.ok) {
                const errData = await resp.json().catch(() => ({}));
                codeContent.innerHTML = '<div class="placeholder-msg" style="color: #f43f5e">Error: ' + escapeHtml(errData.error || 'Failed to load file') + '</div>';
                return;
            }
            analysisData = await resp.json();
            codePaneTitle.textContent = analysisData.fileName || 'Source Code';
            populateScopeSelect();
            unpin();
            renderViewer();
        } catch (err) {
            codeContent.innerHTML = '<div class="placeholder-msg" style="color: #f43f5e">Network error: ' + escapeHtml(err.message) + '</div>';
        }
    }

    function populateScopeSelect() {
        scopeSelect.innerHTML = '<option value="all">All Scopes (Whole File)</option>';
        selectedScope = 'all';
        codeScopeBadge.textContent = 'All Scopes';

        if (analysisData && analysisData.functions && analysisData.functions.length > 0) {
            analysisData.functions.forEach(fn => {
                const opt = document.createElement('option');
                opt.value = fn.name;
                opt.textContent = `Function: ${fn.name} (Lines ${fn.startLine + 1} - ${fn.endLine + 1})`;
                scopeSelect.appendChild(opt);
            });
        }
    }

    function getActiveScopeIntervals() {
        if (!analysisData) return [];
        if (selectedScope === 'all') {
            return analysisData.intervals || [];
        }
        const fn = (analysisData.functions || []).find(f => f.name === selectedScope);
        return fn ? fn.intervals : [];
    }

    function getColumns() {
        const intervals = getActiveScopeIntervals();
        const colMap = new Map();

        intervals.forEach(int => {
            const key = int.keyName.toLowerCase();
            if (!colMap.has(key)) {
                colMap.set(key, {
                    keyName: int.keyName,
                    isRegister: int.isRegister,
                    registerName: int.registerName,
                    allocatedRegister: int.allocatedRegister,
                    isSpill: int.isSpill,
                    intervals: []
                });
            }
            colMap.get(key).intervals.push(int);
        });

        return Array.from(colMap.values()).sort((a, b) => {
            if (a.isRegister !== b.isRegister) return a.isRegister ? 1 : -1;
            return a.keyName.localeCompare(b.keyName);
        });
    }

    function renderViewer() {
        if (!analysisData) return;

        const activeIntervals = getActiveScopeIntervals();
        intervalCount.textContent = activeIntervals.length;

        const regNames = new Set();
        activeIntervals.forEach(i => {
            if (i.isRegister) regNames.add(i.keyName);
            else if (i.allocatedRegister && i.allocatedRegister !== 'SPILL') regNames.add(i.allocatedRegister);
        });
        regCount.textContent = regNames.size;

        renderCodePane();
        renderSwimlaneHeaders();
        renderSwimlaneTracks();
    }

    function renderCodePane() {
        codeContent.innerHTML = '';
        analysisData.lines.forEach(line => {
            const lineEl = document.createElement('div');
            lineEl.className = 'code-line';
            lineEl.dataset.line = line.lineNumber;

            const numSpan = document.createElement('span');
            numSpan.className = 'line-num';
            numSpan.textContent = line.lineNumber + 1;
            lineEl.appendChild(numSpan);

            const textSpan = document.createElement('span');
            textSpan.className = 'line-text';

            if (line.tokens && line.tokens.length > 0) {
                let currentPos = 0;
                line.tokens.forEach(tok => {
                    const idx = line.text.indexOf(tok.text, currentPos);
                    if (idx !== -1) {
                        if (idx > currentPos) {
                            textSpan.appendChild(document.createTextNode(line.text.substring(currentPos, idx)));
                        }

                        const tokSpan = document.createElement('span');
                        tokSpan.className = `tok ${tok.cssClass}`;
                        tokSpan.textContent = tok.text;
                        tokSpan.dataset.line = line.lineNumber;

                        const hasImplicit = (tok.implicitReads && tok.implicitReads.length > 0) || (tok.implicitWrites && tok.implicitWrites.length > 0);
                        if (tok.variableKey || hasImplicit) {
                            tokSpan.classList.add('token-interactive');
                            tokSpan.dataset.var = tok.variableKey || '';
                            tokSpan.dataset.implicitReads = (tok.implicitReads || []).join(',');
                            tokSpan.dataset.implicitWrites = (tok.implicitWrites || []).join(',');
                            tokSpan.dataset.scope = tok.functionScope || '';
                            tokSpan.dataset.write = tok.isWrite ? 'true' : 'false';
                            tokSpan.dataset.read = tok.isRead ? 'true' : 'false';

                            const primaryKey = tok.variableKey || (tok.implicitWrites && tok.implicitWrites[0]) || (tok.implicitReads && tok.implicitReads[0]);
                            if (primaryKey) {
                                tokSpan.addEventListener('mouseenter', (e) => onTokenHover(primaryKey, tok.functionScope, e));
                                tokSpan.addEventListener('mouseleave', () => onTokenLeave());
                                tokSpan.addEventListener('click', (e) => {
                                    e.stopPropagation();
                                    onTokenClick(primaryKey, tok.functionScope, e);
                                });
                            }
                        }

                        textSpan.appendChild(tokSpan);
                        currentPos = idx + tok.text.length;
                    }
                });

                if (currentPos < line.text.length) {
                    textSpan.appendChild(document.createTextNode(line.text.substring(currentPos)));
                }
            } else {
                textSpan.appendChild(document.createTextNode(line.text));
            }

            lineEl.appendChild(textSpan);
            codeContent.appendChild(lineEl);
        });
    }

    function renderSwimlaneHeaders() {
        swimlaneHeaders.innerHTML = '';
        const columns = getColumns();

        columns.forEach((col, idx) => {
            const headerEl = document.createElement('div');
            headerEl.className = 'swimlane-col-header';
            headerEl.dataset.var = col.keyName;
            headerEl.dataset.colIndex = idx;

            const nameEl = document.createElement('span');
            nameEl.className = 'col-name';
            nameEl.textContent = col.keyName;
            nameEl.title = col.keyName;
            headerEl.appendChild(nameEl);

            const tagEl = document.createElement('span');
            tagEl.className = 'col-tag';

            if (viewMode === 'pre') {
                if (col.isRegister) {
                    tagEl.classList.add('tag-reg');
                    tagEl.textContent = 'REG';
                } else {
                    tagEl.classList.add('tag-lbl');
                    tagEl.textContent = 'LBL';
                }
            } else {
                if (col.isSpill) {
                    tagEl.classList.add('tag-spill');
                    tagEl.textContent = 'SPILL';
                } else if (col.allocatedRegister) {
                    tagEl.classList.add('tag-alloc');
                    tagEl.textContent = col.allocatedRegister;
                } else {
                    tagEl.classList.add('tag-reg');
                    tagEl.textContent = col.registerName || 'REG';
                }
            }
            headerEl.appendChild(tagEl);

            headerEl.addEventListener('mouseenter', (e) => onTokenHover(col.keyName, selectedScope !== 'all' ? selectedScope : null, e));
            headerEl.addEventListener('mouseleave', () => onTokenLeave());
            headerEl.addEventListener('click', (e) => {
                e.stopPropagation();
                onTokenClick(col.keyName, selectedScope !== 'all' ? selectedScope : null, e);
            });

            swimlaneHeaders.appendChild(headerEl);
        });
    }

    // Precise physical DOM line Y coordinate measurement
    function getLineY(lineNum) {
        const lineEl = codeContent.querySelector(`.code-line[data-line="${lineNum}"]`);
        if (lineEl) {
            return lineEl.offsetTop;
        }
        return lineNum * LINE_HEIGHT + 8;
    }

    function getLineHeight(lineNum) {
        const lineEl = codeContent.querySelector(`.code-line[data-line="${lineNum}"]`);
        if (lineEl) {
            return lineEl.offsetHeight;
        }
        return LINE_HEIGHT;
    }

    function renderSwimlaneTracks() {
        swimlaneBody.innerHTML = '';
        const columns = getColumns();
        const totalHeight = codeContent.offsetHeight || (analysisData.totalLines * LINE_HEIGHT + 16);
        swimlaneBody.style.height = `${totalHeight}px`;

        // Render function boundary dividing lines across the swimlane
        if (analysisData.functions && analysisData.functions.length > 0) {
            analysisData.functions.forEach(fn => {
                const startDivider = document.createElement('div');
                startDivider.className = 'swimlane-fn-divider swimlane-fn-start';
                startDivider.style.top = `${getLineY(fn.startLine)}px`;
                startDivider.title = `Function Start: ${fn.name} (Line ${fn.startLine + 1})`;
                swimlaneBody.appendChild(startDivider);

                const endDivider = document.createElement('div');
                endDivider.className = 'swimlane-fn-divider swimlane-fn-end';
                endDivider.style.top = `${getLineY(fn.endLine) + getLineHeight(fn.endLine)}px`;
                endDivider.title = `Function End: ${fn.name} (Line ${fn.endLine + 1})`;
                swimlaneBody.appendChild(endDivider);
            });
        }

        columns.forEach((col, idx) => {
            const colTrack = document.createElement('div');
            colTrack.className = 'swimlane-col-track';
            colTrack.style.left = `${idx * COL_WIDTH}px`;
            colTrack.dataset.var = col.keyName;

            // Render each interval within this column track (scoped per function)
            col.intervals.forEach(interval => {
                const bar = document.createElement('div');
                bar.className = 'interval-bar';
                bar.dataset.var = interval.keyName;
                bar.dataset.scope = interval.functionScope || '';

                const startY = getLineY(interval.startLine);
                const endY = getLineY(interval.endLine);
                const endHeight = getLineHeight(interval.endLine);

                bar.style.top = `${startY + 2}px`;
                bar.style.height = `${Math.max(20, (endY - startY) + endHeight - 4)}px`;

                bar.addEventListener('mouseenter', (e) => onTokenHover(interval.keyName, interval.functionScope, e));
                bar.addEventListener('mouseleave', () => onTokenLeave());
                bar.addEventListener('click', (e) => {
                    e.stopPropagation();
                    onTokenClick(interval.keyName, interval.functionScope, e);
                    const targetEl = codeContent.querySelector(`.code-line[data-line="${interval.startLine}"]`);
                    if (targetEl) {
                        targetEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    }
                });

                colTrack.appendChild(bar);

                // Write points
                interval.writes.forEach(w => {
                    const marker = document.createElement('div');
                    marker.className = 'point-marker point-write';
                    marker.dataset.var = interval.keyName;
                    marker.dataset.scope = interval.functionScope || '';
                    marker.dataset.line = w.line;
                    marker.textContent = 'W';
                    marker.title = `Write on line ${w.line + 1} (${interval.functionScope || 'main'})`;
                    marker.style.top = `${getLineY(w.line) + 3}px`;
                    colTrack.appendChild(marker);
                });

                // Read points
                interval.reads.forEach(r => {
                    const marker = document.createElement('div');
                    marker.className = 'point-marker point-read';
                    marker.dataset.var = interval.keyName;
                    marker.dataset.scope = interval.functionScope || '';
                    marker.dataset.line = r.line;
                    marker.textContent = 'R';
                    marker.title = `Read on line ${r.line + 1} (${interval.functionScope || 'main'})`;
                    marker.style.top = `${getLineY(r.line) + 3}px`;
                    colTrack.appendChild(marker);
                });
            });

            swimlaneBody.appendChild(colTrack);
        });
    }

    let renderConnectionsRafId = null;

    function scheduleRenderConnections(key, scope) {
        if (renderConnectionsRafId) {
            cancelAnimationFrame(renderConnectionsRafId);
        }
        renderConnectionsRafId = requestAnimationFrame(() => {
            renderConnections(key, scope);
            updatePinnedTooltipPosition();
        });
    }

    // Interaction handlers
    function onTokenHover(varKey, scope, event) {
        if (isPinned) return;
        activeKey = varKey;
        activeScope = scope;
        highlightKey(varKey, scope);
        scheduleRenderConnections(varKey, scope);
        showTooltipFor(varKey, scope, event);
    }

    function onTokenLeave() {
        if (isPinned) return;
        activeKey = null;
        activeScope = null;
        clearHighlights();
        hideTooltip();
    }

    function onTokenClick(varKey, scope, event) {
        if (isPinned && activeKey === varKey && activeScope === scope) {
            unpin();
        } else {
            pin(varKey, scope);
            showTooltipFor(varKey, scope, event);
        }
    }

    function tokenMatchesKey(tokEl, key) {
        if (!key) return false;
        const lowerKey = key.toLowerCase();
        const explicitVar = (tokEl.dataset.var || '').toLowerCase();
        if (explicitVar === lowerKey) return true;
        const impReads = (tokEl.dataset.implicitReads || '').toLowerCase().split(',').filter(Boolean);
        if (impReads.includes(lowerKey)) return true;
        const impWrites = (tokEl.dataset.implicitWrites || '').toLowerCase().split(',').filter(Boolean);
        if (impWrites.includes(lowerKey)) return true;
        return false;
    }

    function isTokenWriteForKey(tokEl, key) {
        if (!key) return false;
        const lowerKey = key.toLowerCase();
        if ((tokEl.dataset.var || '').toLowerCase() === lowerKey && tokEl.dataset.write === 'true') return true;
        const impWrites = (tokEl.dataset.implicitWrites || '').toLowerCase().split(',').filter(Boolean);
        return impWrites.includes(lowerKey);
    }

    function isTokenReadForKey(tokEl, key) {
        if (!key) return false;
        const lowerKey = key.toLowerCase();
        if ((tokEl.dataset.var || '').toLowerCase() === lowerKey && tokEl.dataset.read === 'true') return true;
        const impReads = (tokEl.dataset.implicitReads || '').toLowerCase().split(',').filter(Boolean);
        return impReads.includes(lowerKey);
    }

    function highlightKey(key, scope) {
        clearHighlights();

        // Highlight matching tokens in code pane (scoped to active function if set)
        const tokens = codeContent.querySelectorAll('.token-interactive');
        tokens.forEach(tok => {
            const tokScope = tok.dataset.scope;
            if (tokenMatchesKey(tok, key)) {
                if (!scope || !tokScope || tokScope === scope) {
                    tok.classList.add('token-active');
                    if (isTokenWriteForKey(tok, key)) {
                        tok.classList.add('var-write');
                    } else if (isTokenReadForKey(tok, key)) {
                        tok.classList.add('var-read');
                    }
                }
            }
        });

        // Highlight matching swimlane headers and bars
        const headers = swimlaneHeaders.querySelectorAll(`[data-var="${key}"]`);
        headers.forEach(h => h.classList.add('active-col'));

        const bars = swimlaneBody.querySelectorAll(`.interval-bar[data-var="${key}"]`);
        bars.forEach(b => {
            if (!scope || !b.dataset.scope || b.dataset.scope === scope) {
                b.classList.add('active-bar');
            }
        });
    }

    function clearHighlights() {
        codeContent.querySelectorAll('.token-active').forEach(el => {
            el.classList.remove('token-active', 'var-write', 'var-read');
        });
        swimlaneHeaders.querySelectorAll('.active-col').forEach(el => el.classList.remove('active-col'));
        swimlaneBody.querySelectorAll('.active-bar').forEach(el => el.classList.remove('active-bar'));
        svgPathsGroup.innerHTML = '';
    }

    // Dynamic SVG Bezier Connections (Optimized & Viewport-Clipped)
    function renderConnections(key, scope) {
        svgPathsGroup.innerHTML = '';
        if (!key) return;

        const wrapperRect = splitWrapper.getBoundingClientRect();
        const codePaneRect = codePane.getBoundingClientRect();
        const visibleTop = codePaneRect.top;
        const visibleBottom = codePaneRect.bottom;

        // 1. Find matching interactive tokens for key and scope
        const allTokens = Array.from(codeContent.querySelectorAll('.token-interactive')).filter(t => {
            const matchesKey = tokenMatchesKey(t, key);
            const matchesScope = !scope || !t.dataset.scope || t.dataset.scope === scope;
            return matchesKey && matchesScope;
        });

        if (allTokens.length === 0) return;

        // 2. Only consider tokens visible in current viewport (plus 40px buffer)
        const visibleTokens = allTokens.filter(t => {
            const rect = t.getBoundingClientRect();
            return rect.bottom >= visibleTop - 40 && rect.top <= visibleBottom + 40;
        });

        // 3. Find matching swimlane point markers (writes and reads)
        const allMarkers = Array.from(swimlaneBody.querySelectorAll(`.point-marker[data-var="${key}"]`)).filter(m => {
            return !scope || !m.dataset.scope || m.dataset.scope === scope;
        });

        const markerByLine = new Map();
        allMarkers.forEach(m => {
            markerByLine.set(m.dataset.line, m);
        });

        // 4. Find matching interval bars
        const intervalBars = Array.from(swimlaneBody.querySelectorAll(`.interval-bar[data-var="${key}"]`)).filter(b => {
            return !scope || !b.dataset.scope || b.dataset.scope === scope;
        });

        const paths = [];

        // Connection Type 1: Horizontal connectors from each visible code token to its swimlane marker (or interval bar)
        visibleTokens.forEach(tok => {
            const line = tok.dataset.line;
            const tRect = tok.getBoundingClientRect();
            const tx = tRect.right - wrapperRect.left;
            const ty = tRect.top + tRect.height / 2 - wrapperRect.top;

            const marker = markerByLine.get(line);
            if (marker) {
                const mRect = marker.getBoundingClientRect();
                const mx = mRect.left - wrapperRect.left;
                const my = mRect.top + mRect.height / 2 - wrapperRect.top;
                paths.push({ x1: tx, y1: ty, x2: mx, y2: my, cls: 'bezier-connection bezier-swimlane' });
            } else if (intervalBars.length > 0) {
                const bRect = intervalBars[0].getBoundingClientRect();
                const bx = bRect.left - wrapperRect.left;
                paths.push({ x1: tx, y1: ty, x2: bx, y2: ty, cls: 'bezier-connection bezier-swimlane' });
            }
        });

        // Connection Type 2: Def-Use flows between visible tokens in code
        // Connect each visible write to its subsequent reads within the scope (max 20 curves)
        const writes = visibleTokens.filter(t => isTokenWriteForKey(t, key));
        const reads = visibleTokens.filter(t => isTokenReadForKey(t, key));

        if (writes.length > 0 && reads.length > 0) {
            writes.forEach(w => {
                const wLine = parseInt(w.dataset.line, 10);
                const wRect = w.getBoundingClientRect();
                const wx = wRect.left - wrapperRect.left;
                const wy = wRect.top + wRect.height / 2 - wrapperRect.top;

                // Find reads that follow this write until next write
                const nextWrite = writes.find(w2 => parseInt(w2.dataset.line, 10) > wLine);
                const nextWriteLine = nextWrite ? parseInt(nextWrite.dataset.line, 10) : Infinity;

                const targetReads = reads.filter(r => {
                    const rLine = parseInt(r.dataset.line, 10);
                    return rLine > wLine && rLine <= nextWriteLine;
                });

                targetReads.slice(0, 5).forEach(r => {
                    const rRect = r.getBoundingClientRect();
                    const rx = rRect.left - wrapperRect.left;
                    const ry = rRect.top + rRect.height / 2 - wrapperRect.top;
                    paths.push({ x1: wx, y1: wy, x2: rx, y2: ry, cls: 'bezier-connection bezier-def-use', arcLeft: true });
                });
            });
        }

        // 5. Batch render all paths in a single DOM update (capped to max 60 paths)
        let svgHtml = '';
        const maxPaths = Math.min(paths.length, 60);
        for (let i = 0; i < maxPaths; i++) {
            const p = paths[i];
            const d = createBezierPath(p.x1, p.y1, p.x2, p.y2, p.arcLeft);
            svgHtml += `<path d="${d}" class="${p.cls}" />`;
        }
        svgPathsGroup.innerHTML = svgHtml;
    }

    function createBezierPath(x1, y1, x2, y2, arcLeft) {
        if (arcLeft) {
            const dy = Math.abs(y2 - y1);
            const curveOffset = Math.min(60, Math.max(20, dy * 0.3));
            const cx = Math.min(x1, x2) - curveOffset;
            return `M ${x1} ${y1} C ${cx} ${y1}, ${cx} ${y2}, ${x2} ${y2}`;
        } else {
            const dx = Math.abs(x2 - x1);
            const curveOffset = Math.max(30, dx * 0.45);
            const cx1 = x1 + curveOffset;
            const cx2 = x2 - curveOffset;
            return `M ${x1} ${y1} C ${cx1} ${y1}, ${cx2} ${y2}, ${x2} ${y2}`;
        }
    }

    // Tooltip display and positioning
    function getIntervalFor(key, scope) {
        if (!analysisData || !key) return null;
        const intervals = getActiveScopeIntervals();
        if (scope) {
            const match = intervals.find(i =>
                i.keyName.toLowerCase() === key.toLowerCase() &&
                i.functionScope === scope
            );
            if (match) return match;
        }

        const matching = intervals.filter(i => i.keyName.toLowerCase() === key.toLowerCase());
        if (matching.length === 0) return null;
        if (matching.length === 1) return matching[0];

        const currentScroll = codePane.scrollTop;
        const visibleInterval = matching.find(i => {
            const sY = getLineY(i.startLine);
            const eY = getLineY(i.endLine) + getLineHeight(i.endLine);
            return eY >= currentScroll && sY <= currentScroll + codePane.clientHeight;
        });
        return visibleInterval || matching[0];
    }

    function updatePinnedTooltipPosition() {
        if (!isPinned || isTooltipDismissed || !activeKey) return;
        if (infoTooltip.classList.contains('hidden')) return;

        const interval = getIntervalFor(activeKey, activeScope);
        if (!interval) return;

        const headerHeight = swimlaneHeaders.offsetHeight || 48;
        const viewportTop = headerHeight + 8;
        const viewportBottom = splitWrapper.clientHeight - 8;

        let spanTop, spanBottom;
        const bar = swimlaneBody.querySelector(
            `.interval-bar[data-var="${interval.keyName}"]` +
            (interval.functionScope ? `[data-scope="${interval.functionScope}"]` : '')
        );

        if (bar) {
            const wrapperRect = splitWrapper.getBoundingClientRect();
            const barRect = bar.getBoundingClientRect();
            spanTop = barRect.top - wrapperRect.top;
            spanBottom = barRect.bottom - wrapperRect.top;
        } else {
            const startY = getLineY(interval.startLine);
            const endY = getLineY(interval.endLine);
            const endHeight = getLineHeight(interval.endLine);
            const scrollTop = swimlanePane.scrollTop;
            spanTop = (startY + 2) - scrollTop + headerHeight;
            spanBottom = (endY + endHeight - 2) - scrollTop + headerHeight;
        }

        // 1. Scroll-off check: hide if span line is entirely scrolled out of viewport
        if (spanBottom <= viewportTop || spanTop >= viewportBottom) {
            infoTooltip.style.display = 'none';
            return;
        }

        infoTooltip.style.display = 'block';

        const boxHeight = infoTooltip.offsetHeight || 190;
        const spanHeight = spanBottom - spanTop;

        let boxTop;
        if (spanHeight <= boxHeight) {
            // Span line is smaller than hover box: align with highest portion of visible span line
            boxTop = Math.max(spanTop, viewportTop);
        } else {
            // Span line is larger than hover box: do not scroll past the span line
            boxTop = Math.max(spanTop, Math.min(viewportTop, spanBottom - boxHeight));
        }

        infoTooltip.style.top = `${boxTop}px`;
        infoTooltip.style.right = '20px';
        infoTooltip.style.left = 'auto';
        infoTooltip.style.bottom = 'auto';
    }

    function showTooltipFor(key, scope, event) {
        if (!analysisData) return;
        if (isPinned && isTooltipDismissed) return;

        const interval = getIntervalFor(key, scope);
        if (!interval) return;

        ttVarName.textContent = interval.keyName;
        ttBadge.textContent = interval.isRegister ? 'Hardware Register' : 'Register Label';

        if (interval.functionScope) {
            ttScopeRow.style.display = 'flex';
            ttScope.textContent = interval.functionScope;
        } else {
            ttScopeRow.style.display = 'none';
        }

        ttSpan.textContent = `Lines ${interval.startLine + 1} - ${interval.endLine + 1} (${interval.endLine - interval.startLine + 1} lines)`;

        if (viewMode === 'post') {
            ttAllocRow.style.display = 'flex';
            if (interval.isSpill) {
                ttAlloc.textContent = 'SPILL';
                ttAlloc.style.color = '#f43f5e';
            } else if (interval.allocatedRegister) {
                ttAlloc.textContent = interval.allocatedRegister;
                ttAlloc.style.color = '#10b981';
            } else {
                ttAlloc.textContent = interval.registerName || 'REG';
                ttAlloc.style.color = '#f59e0b';
            }
        } else {
            ttAllocRow.style.display = 'none';
        }

        const writesList = interval.writes.map(w => w.line + 1);
        ttWrites.textContent = writesList.length > 0 ? `Lines: ${writesList.join(', ')}` : 'None';

        const readsList = interval.reads.map(r => r.line + 1);
        ttReads.textContent = readsList.length > 0 ? `Lines: ${readsList.join(', ')}` : 'None';

        ttInterferes.textContent = interval.interferences.length > 0 ? interval.interferences.join(', ') : 'None';

        infoTooltip.classList.remove('hidden');
        infoTooltip.style.display = 'block';

        if (isPinned) {
            updatePinnedTooltipPosition();
        } else if (event && event.clientX) {
            const wrapperRect = splitWrapper.getBoundingClientRect();
            let left = event.clientX - wrapperRect.left + 16;
            let top = event.clientY - wrapperRect.top + 16;
            if (left + 310 > wrapperRect.width) left = (event.clientX - wrapperRect.left) - 315;
            if (top + 230 > wrapperRect.height) top = (event.clientY - wrapperRect.top) - 235;
            infoTooltip.style.left = `${left}px`;
            infoTooltip.style.top = `${top}px`;
            infoTooltip.style.right = 'auto';
            infoTooltip.style.bottom = 'auto';
        } else {
            infoTooltip.style.right = '20px';
            infoTooltip.style.top = '56px';
            infoTooltip.style.left = 'auto';
            infoTooltip.style.bottom = 'auto';
        }
    }

    function hideTooltip() {
        infoTooltip.classList.add('hidden');
        infoTooltip.style.display = 'none';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    document.addEventListener('DOMContentLoaded', init);
})();
