function mountSplitOptions(container, fileInput) {
    container.innerHTML = `
        <div class="form-group" data-strategy>
            <label>切片策略
                <select name="splitType">
                    <option value="">按文件类型自动选择（推荐）</option>
                    <option value="LENGTH">按长度切分</option>
                    <option value="TITLE">按标题切分</option>
                    <option value="REGEX">按正则切分</option>
                    <option value="SMART">智能切分</option>
                    <option value="SEPARATOR">按分隔符切分</option>
                </select>
            </label>
            <p class="param-hint" data-default></p>
        </div>
        <p class="form-group param-hint" data-table hidden>表格固定按表头和数据行切片，每个数据行生成一条。</p>
        <div class="form-group" data-for="LENGTH TITLE REGEX SMART SEPARATOR" hidden>
            <label>最大切片长度（字符）
                <input name="chunkSize" type="number" min="1" max="100000" placeholder="留空用默认值">
            </label>
        </div>
        <div class="form-group" data-for="LENGTH TITLE REGEX SEPARATOR" hidden>
            <label>相邻切片重叠长度
                <input name="overlap" type="number" min="0" placeholder="留空用默认值">
            </label>
        </div>
        <div class="form-group" data-for="TITLE" hidden>
            <label>切到第几级标题
                <input name="titleLevel" type="number" min="1" max="6" placeholder="留空用默认值">
            </label>
        </div>
        <div class="form-group" data-for="REGEX" hidden>
            <label>切片正则表达式
                <input name="regex" type="text" placeholder="例如：\\n\\n+" required>
            </label>
        </div>
        <div class="form-group" data-for="SEPARATOR" hidden>
            <label>分隔符表达式
                <input name="separator" type="text" placeholder="例如：\\n\\n" required>
            </label>
        </div>`;
    const strategy = container.querySelector('select');
    const defaultHint = container.querySelector('[data-default]');
    let filename = '';

    function update(name = fileInput?.files[0]?.name || filename) {
        filename = name;
        const ext = filename.split('.').pop().toLowerCase();
        const table = ['csv', 'xlsx', 'xls'].includes(ext);
        strategy.disabled = table;
        container.querySelector('[data-strategy]').hidden = table;
        container.querySelector('[data-table]').hidden = !table;
        defaultHint.hidden = Boolean(strategy.value);
        defaultHint.textContent = ({
            pdf: '默认按二级标题，900 字符，重叠 120 字符。',
            doc: '默认按二级标题，900 字符，重叠 120 字符。',
            docx: '默认按二级标题，900 字符，重叠 120 字符。',
            md: '默认按三级标题，900 字符，重叠 120 字符。',
            txt: '默认按长度，600 字符，重叠 80 字符。'
        })[ext] || '选择文件后显示默认策略。';
        for (const group of container.querySelectorAll('[data-for]')) {
            const enabled = !table && group.dataset.for.split(' ').includes(strategy.value);
            group.hidden = !enabled;
            group.querySelector('input').disabled = !enabled;
        }
    }

    function appendTo(params) {
        for (const field of container.querySelectorAll('select, input')) {
            if (!field.disabled && field.value !== '') params.set(field.name, field.value);
        }
    }

    strategy.addEventListener('change', () => update());
    fileInput?.addEventListener('change', () => update(fileInput.files[0]?.name || ''));
    update();
    return { update, appendTo };
}

function describeVersion(v, active) {
    if (active) return '已发布';
    if (v.lastError) return v.nextRetryAt ? '处理失败，等待自动重试' : '处理失败';
    if (v.indexStatus === 'READY') return '索引就绪，尚未生效';
    if (v.indexStatus === 'PENDING') return '正在写入和核验索引';
    if (v.buildStatus === 'CONVERTED') return v.splitParam ? '已解析，等待切片' : '已解析，未提交构建';
    if (v.buildStatus === 'UPLOADED') return v.splitParam ? '已接收，排队或正在解析' : '已上传，未提交构建';
    return '正在保存原文件';
}

function versionBuildPending(v, active) {
    return !active && v.indexStatus !== 'READY' && (!v.lastError || Boolean(v.nextRetryAt)) && Boolean(v.splitParam);
}
