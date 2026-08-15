import type { ReactNode } from 'react';

interface ConsoleTableProps<T> {
  rows: T[];
  columns: Array<{
    key: string;
    title: string;
    render: (row: T) => ReactNode;
    className?: string;
  }>;
  emptyText?: string;
}

export function ConsoleTable<T>({ rows, columns, emptyText = '暂无数据' }: ConsoleTableProps<T>) {
  return (
    <div className="overflow-x-auto rounded-lg border border-cyan-300/10">
      <table className="min-w-full border-collapse text-left text-sm">
        <thead className="bg-cyan-300/[0.07] text-xs text-cyan-100/64">
          <tr>
            {columns.map((column) => (
              <th key={column.key} className={['whitespace-nowrap px-3 py-2 font-medium', column.className].filter(Boolean).join(' ')}>
                {column.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-cyan-300/10">
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-3 py-8 text-center text-cyan-100/45">
                {emptyText}
              </td>
            </tr>
          ) : rows.map((row, index) => (
            <tr key={index} className="bg-white/[0.025] text-cyan-50/82 hover:bg-cyan-300/[0.045]">
              {columns.map((column) => (
                <td key={column.key} className={['max-w-[260px] whitespace-nowrap px-3 py-2 align-middle', column.className].filter(Boolean).join(' ')}>
                  <div className="truncate">{column.render(row)}</div>
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
