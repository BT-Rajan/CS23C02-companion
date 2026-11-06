marks = [72, 85, 64]
total = 0
for m in marks:
    total = total + m
average = total / len(marks)
print("Total:", total)
print("Average:", round(average, 1))
