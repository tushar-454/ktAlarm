import { DatePicker, Host } from '@expo/ui/swift-ui';
import { datePickerStyle } from '@expo/ui/swift-ui/modifiers';
import { useMemo } from 'react';

import type { TimePickerProps } from './time-picker.types';

/** Native SwiftUI wheel time picker. */
export function TimePicker({ hour, minute, onChange }: TimePickerProps) {
  const selection = useMemo(() => {
    const d = new Date();
    d.setHours(hour, minute, 0, 0);
    return d;
  }, [hour, minute]);

  return (
    <Host matchContents>
      <DatePicker
        displayedComponents={['hourAndMinute']}
        selection={selection}
        onDateChange={(date) => onChange(date.getHours(), date.getMinutes())}
        modifiers={[datePickerStyle('wheel')]}
      />
    </Host>
  );
}
