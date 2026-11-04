UPDATE sys_user SET name=CASE username WHEN 'student' THEN '学生' WHEN 'labstaff' THEN '实验员' WHEN 'technician' THEN '维修人员' ELSE name END WHERE username IN ('student','labstaff','technician');
